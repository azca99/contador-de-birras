package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.FriendProfile
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FriendsRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    suspend fun addFriendByEmailOrUsername(searchQuery: String): String? {
        val currentUser = auth.currentUser ?: return "Error de autenticación"
        val normalizedSearch = searchQuery.lowercase().trim()

        try {
            val result = functions.getHttpsCallable("searchUser").call(mapOf("query" to normalizedSearch)).await()
            val data = result.data as? Map<String, Any> ?: return "Error desconocido"
            val found = data["found"] as? Boolean ?: false

            if (!found) {
                return "No se encontró ningún usuario con ese email o username."
            }

            val friendUid = data["uid"] as String
            if (friendUid == currentUser.uid) {
                return "No puedes añadirte a ti mismo."
            }

            // Create friendship request
            val friendshipId = if (currentUser.uid < friendUid) "${currentUser.uid}_$friendUid" else "${friendUid}_${currentUser.uid}"
            
            val friendshipData = hashMapOf(
                "user1" to (if (currentUser.uid < friendUid) currentUser.uid else friendUid),
                "user2" to (if (currentUser.uid > friendUid) currentUser.uid else friendUid),
                "status" to "PENDING",
                "requester" to currentUser.uid, "friendshipId" to friendshipId
            )

            firestore.collection("friendships").document(friendshipId).set(friendshipData).await()
            return null
        } catch (e: Exception) {
            android.util.Log.e("SearchDebug", "Error al buscar amigo", e)
            return "Ocurrió un error al intentar añadir al amigo. Revisa tu conexión."
        }
    }

    suspend fun acceptFriendRequest(friendshipId: String) {
        try {
            firestore.collection("friendships").document(friendshipId)
                .update("status", "ACCEPTED").await()
        } catch (e: Exception) { }
    }

    suspend fun rejectFriendRequest(friendshipId: String) {
        try {
            firestore.collection("friendships").document(friendshipId).delete().await()
        } catch (e: Exception) { }
    }

        fun getFriends(): Flow<List<FriendProfile>> = callbackFlow {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        var snap1Docs: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()
        var snap2Docs: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()

        fun updateProfiles() {
            val allFriendships = mutableMapOf<String, Map<String, Any>>()
            
            snap1Docs.forEach { d ->
                val u2 = d.getString("user2")
                if (u2 != null) allFriendships[u2] = mapOf("id" to d.id, "status" to (d.getString("status") ?: ""), "requester" to (d.getString("requester") ?: ""))
            }
            snap2Docs.forEach { d ->
                val u1 = d.getString("user1")
                if (u1 != null) allFriendships[u1] = mapOf("id" to d.id, "status" to (d.getString("status") ?: ""), "requester" to (d.getString("requester") ?: ""))
            }

            if (allFriendships.isEmpty()) {
                trySend(emptyList())
                return
            }

            val profiles = mutableListOf<FriendProfile>()
            val uids = allFriendships.keys.toList()
            val chunks = uids.chunked(10)
            var completed = 0

            chunks.forEach { chunk ->
                firestore.collection("publicUsers").whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                    .get().addOnSuccessListener { pubSnap ->
                        pubSnap.documents.forEach { doc ->
                            val fData = allFriendships[doc.id]
                            profiles.add(
                                FriendProfile(
                                    uid = doc.id,
                                    alias = doc.getString("displayName") ?: doc.getString("username") ?: "Usuario",
                                    photoUrl = doc.getString("photoUrl"),
                                    status = fData?.get("status") as? String ?: "PENDING",
                                    requester = fData?.get("requester") as? String ?: "",
                                    friendshipId = fData?.get("id") as? String ?: ""
                                )
                            )
                        }
                        completed++
                        if (completed == chunks.size) {
                            trySend(profiles)
                        }
                    }.addOnFailureListener {
                        completed++
                        if (completed == chunks.size) {
                            trySend(profiles)
                        }
                    }
            }
        }

        val listenerReg1 = firestore.collection("friendships")
            .whereEqualTo("user1", currentUser.uid)
            .addSnapshotListener { snap1, _ ->
                if (snap1 != null) {
                    snap1Docs = snap1.documents
                    updateProfiles()
                }
            }

        val listenerReg2 = firestore.collection("friendships")
            .whereEqualTo("user2", currentUser.uid)
            .addSnapshotListener { snap2, _ ->
                if (snap2 != null) {
                    snap2Docs = snap2.documents
                    updateProfiles()
                }
            }

        awaitClose { 
            listenerReg1.remove()
            listenerReg2.remove()
        }
    }

    fun getFriendBeers(friendUid: String): Flow<List<BeerEntity>> = callbackFlow {
        val listenerRegistration = firestore.collection("sharedBeers")
            .whereEqualTo("userId", friendUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val beers = snapshot.documents.mapNotNull { doc ->
                    try {
                        BeerEntity(
                            id = 0,
                            type = BeerType.valueOf(doc.getString("type") ?: "RUBIA"),
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            latitude = doc.getDouble("latitude"),
                            longitude = doc.getDouble("longitude"),
                            photoUri = null,
                            comment = doc.getString("comment"),
                            locationName = doc.getString("locationName"),
                            syncId = doc.id,
                            syncStatus = SyncStatus.SYNCED,
                            remotePhotoUrl = doc.getString("remotePhotoUrl"),
                            updatedAt = doc.getLong("updatedAt") ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.timestamp }
                trySend(beers)
            }

        awaitClose { listenerRegistration.remove() }
    }

    suspend fun addFriendByUid(friendUid: String): String? {
        val currentUser = auth.currentUser ?: return "Error de autenticacion"
        if (friendUid == currentUser.uid) { return "No puedes aadirte a ti mismo." }
        try {
            val friendshipId = if (currentUser.uid < friendUid) "${currentUser.uid}_${friendUid}" else "${friendUid}_${currentUser.uid}"
            val friendshipData = hashMapOf(
                "user1" to (if (currentUser.uid < friendUid) currentUser.uid else friendUid),
                "user2" to (if (currentUser.uid > friendUid) currentUser.uid else friendUid),
                "status" to "PENDING",
                "requester" to currentUser.uid, "friendshipId" to friendshipId
            )
            firestore.collection("friendships").document(friendshipId).set(friendshipData).await()
            return null
        } catch (e: Exception) { return "Ocurrio error" }
    }
}