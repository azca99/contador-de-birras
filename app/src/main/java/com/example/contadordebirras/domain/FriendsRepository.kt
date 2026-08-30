package com.example.contadordebirras.domain

import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.data.FriendProfile
import com.example.contadordebirras.data.SyncStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FriendsRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    suspend fun addFriendByEmailOrUsername(searchQuery: String): String? {
        val currentUser = auth.currentUser ?: return "Error de autenticación"
        val normalizedSearch = searchQuery.lowercase().trim()
        
        android.util.Log.d("SearchDebug", "username introducido: $searchQuery")
        android.util.Log.d("SearchDebug", "username normalizado: $normalizedSearch")
        
        try {
            // Primero buscar por email
            var querySnapshot = firestore.collection("publicUsers")
                .whereEqualTo("emailLowercase", normalizedSearch)
                .limit(1)
                .get()
                .await()
                
            // Si no encuentra por email, buscar por username
            if (querySnapshot.isEmpty) {
                querySnapshot = firestore.collection("publicUsers")
                    .whereEqualTo("usernameLowercase", normalizedSearch)
                    .limit(1)
                    .get()
                    .await()
            }
                
            android.util.Log.d("SearchDebug", "número de resultados encontrados: ${querySnapshot.size()}")

            if (!querySnapshot.isEmpty) {
                val friendDoc = querySnapshot.documents[0]
                val friendUid = friendDoc.id
                
                android.util.Log.d("SearchDebug", "uid del resultado encontrado: $friendUid")
                
                if (friendUid == currentUser.uid) {
                    android.util.Log.d("SearchDebug", "El usuario intentó añadirse a sí mismo")
                    return "No puedes añadirte a ti mismo como amigo."
                }

                // Add friendUid to current user's friends array
                firestore.collection("users").document(currentUser.uid)
                    .set(mapOf("friends" to FieldValue.arrayUnion(friendUid)), com.google.firebase.firestore.SetOptions.merge())
                    .await()
                return null
            } else {
                return "No se encontró ningún usuario con ese email o username."
            }
        } catch (e: Exception) {
            android.util.Log.e("SearchDebug", "Error al buscar amigo", e)
            return "Ocurrió un error al intentar añadir al amigo."
        }
    }

    fun getFriends(): Flow<List<FriendProfile>> = callbackFlow {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore.collection("users").document(currentUser.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val friendsUids = snapshot.get("friends") as? List<String> ?: emptyList()
                    if (friendsUids.isEmpty()) {
                        trySend(emptyList())
                    } else {
                        val profiles = mutableListOf<FriendProfile>()
                        val chunks = friendsUids.chunked(10)
                        
                        var completedChunks = 0
                        chunks.forEach { chunk ->
                            firestore.collection("publicUsers")
                                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                                .get()
                                .addOnSuccessListener { friendsSnapshot ->
                                    profiles.addAll(friendsSnapshot.documents.mapNotNull { doc ->
                                        val alias = doc.getString("displayName") ?: doc.getString("alias") ?: ""
                                        val userEmail = doc.getString("email") ?: ""
                                        FriendProfile(uid = doc.id, email = userEmail, alias = alias)
                                    })
                                    completedChunks++
                                    if (completedChunks == chunks.size) {
                                        trySend(profiles)
                                    }
                                }
                        }
                    }
                } else {
                    trySend(emptyList())
                }
            }

        awaitClose { listenerRegistration.remove() }
    }

    fun getFriendBeers(friendUid: String): Flow<List<BeerEntity>> = callbackFlow {
        val listenerRegistration = firestore.collection("beers")
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
}
