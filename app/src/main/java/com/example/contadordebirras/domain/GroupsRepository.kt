package com.example.contadordebirras.domain

import com.example.contadordebirras.domain.GroupEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class GroupsRepository(private val beerRepository: BeerRepository? = null) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    fun getGroups(): Flow<List<GroupEntity>> = callbackFlow {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore.collection("groups")
            .whereArrayContains("members", currentUser.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val groups = snapshot.documents.mapNotNull { doc ->
                    try {
                        GroupEntity(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            adminUid = doc.getString("adminUid") ?: "",
                            members = doc.get("members") as? List<String> ?: emptyList(),
                            createdAt = doc.getLong("createdAt") ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(groups)
            }

        awaitClose { listenerRegistration.remove() }
    }

    suspend fun createGroup(name: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        if (name.isBlank() || name.length > 50) return false

        return try {
            val groupData = hashMapOf(
                "name" to name.trim(),
                "adminUid" to currentUser.uid,
                "members" to listOf(currentUser.uid),
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection("groups").add(groupData).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun addMemberByEmailOrUsername(groupId: String, searchQuery: String): String? {
        val normalizedSearch = searchQuery.lowercase().trim()
        val currentUser = auth.currentUser ?: return "Error de autenticación"

        return try {
            val result = functions.getHttpsCallable("searchUser").call(mapOf("query" to normalizedSearch)).await()
            val data = result.data as? Map<String, Any> ?: return "Error de servidor"
            val found = data["found"] as? Boolean ?: false
            
            if (!found) return "No se encontró ningún usuario con ese email o username."
            val uid = data["uid"] as String

            firestore.collection("groups").document(groupId)
                .update("members", FieldValue.arrayUnion(uid))
                .await()

            null
        } catch (e: Exception) {
            if (e.message?.contains("PERMISSION_DENIED") == true) {
                "No tienes permisos (solo el admin puede añadir)."
            } else {
                "Error al añadir al miembro."
            }
        }
    }

    suspend fun removeMemberFromGroup(groupId: String, memberUid: String): Boolean {
        return try {
            firestore.collection("groups").document(groupId)
                .update("members", FieldValue.arrayRemove(memberUid))
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteGroup(groupId: String): Boolean {
        return try {
            firestore.collection("groups").document(groupId).delete().await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getGroupRanking(groupId: String): List<GroupMemberRanking> {
        return try {
            val result = functions.getHttpsCallable("getGroupRanking").call(mapOf("groupId" to groupId)).await()
            val data = result.data as? Map<String, Any> ?: return emptyList()
            val rankingsData = data["rankings"] as? List<Map<String, Any>> ?: return emptyList()
            
            rankingsData.map { item ->
                GroupMemberRanking(
                    uid = item["uid"] as? String ?: "",
                    alias = item["alias"] as? String ?: "",
                    historicalBeers = (item["historicalBeers"] as? Number)?.toInt() ?: 0,
                    monthlyBeers = (item["monthlyBeers"] as? Number)?.toInt() ?: 0,
                    weeklyBeers = (item["weeklyBeers"] as? Number)?.toInt() ?: 0
                )
            }.sortedByDescending { it.historicalBeers }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getGroupMembers(groupId: String): Flow<List<GroupMemberDetail>> = callbackFlow {
        val listenerRegistration = firestore.collection("groups").document(groupId)
            .addSnapshotListener { groupDoc, error ->
                if (error != null || groupDoc == null || !groupDoc.exists()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val membersUids = groupDoc.get("members") as? List<String> ?: emptyList()
                if (membersUids.isEmpty()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val profiles = mutableListOf<GroupMemberDetail>()
                val chunks = membersUids.chunked(10)
                var completedChunks = 0

                chunks.forEach { chunk ->
                    firestore.collection("publicUsers")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                        .get()
                        .addOnSuccessListener { snapshot ->
                            profiles.addAll(snapshot.documents.mapNotNull { doc ->
                                try {
                                    val alias = doc.getString("displayName") ?: doc.getString("alias") ?: "Anónimo"
                                    GroupMemberDetail(
                                        uid = doc.id,
                                        displayName = alias,
                                        username = doc.getString("username"),
                                        photoUrl = doc.getString("photoUrl")
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                            })
                            completedChunks++
                            if (completedChunks == chunks.size) {
                                trySend(profiles)
                            }
                        }
                        .addOnFailureListener {
                            completedChunks++
                            if (completedChunks == chunks.size) {
                                trySend(profiles)
                            }
                        }
                }
            }

        awaitClose { listenerRegistration.remove() }
    }

    fun getGroupComments(groupId: String): Flow<List<GroupComment>> = callbackFlow {
        val listenerRegistration = firestore.collection("groups").document(groupId).collection("comments")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val comments = snapshot.documents.mapNotNull { doc ->
                    try {
                        GroupComment(
                            commentId = doc.id,
                            authorUid = doc.getString("authorUid") ?: "",
                            authorName = doc.getString("authorName") ?: "Anónimo",
                            authorUsername = doc.getString("authorUsername"),
                            text = doc.getString("text") ?: "",
                            createdAt = doc.getLong("createdAt") ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(comments)
            }

        awaitClose { listenerRegistration.remove() }
    }

    suspend fun addComment(groupId: String, text: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        val cleanText = text.trim()
        if (cleanText.isEmpty() || cleanText.length > 300) return false

        return try {
            val userProfile = firestore.collection("publicUsers").document(currentUser.uid).get().await()
            val authorName = userProfile.getString("displayName") ?: userProfile.getString("username") ?: "Anónimo"
            val authorUsername = userProfile.getString("username")
            
            val commentData = hashMapOf(
                "authorUid" to currentUser.uid,
                "authorName" to authorName,
                "authorUsername" to authorUsername,
                "text" to cleanText,
                "createdAt" to System.currentTimeMillis()
            )
            
            firestore.collection("groups").document(groupId).collection("comments").add(commentData).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}

