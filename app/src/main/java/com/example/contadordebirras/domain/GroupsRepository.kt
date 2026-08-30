package com.example.contadordebirras.domain

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class GroupsRepository(private val beerRepository: BeerRepository? = null) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

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
                    android.util.Log.e("GroupsDebug", "Error listening to groups", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val groups = snapshot.documents.mapNotNull { doc ->
                    try {
                        val members = doc.get("members") as? List<String> ?: emptyList()
                        GroupEntity(
                            id = doc.id,
                            name = doc.getString("name") ?: "Grupo sin nombre",
                            adminUid = doc.getString("adminUid") ?: "",
                            members = members,
                            createdAt = doc.getLong("createdAt") ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedByDescending { it.createdAt }
                trySend(groups)
            }

        awaitClose { listenerRegistration.remove() }
    }

    suspend fun createGroup(name: String): Boolean {
        val currentUser = auth.currentUser ?: return false
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
        return try {
            val normalizedSearch = searchQuery.lowercase().trim()
            
            var querySnapshot = firestore.collection("publicUsers")
                .whereEqualTo("emailLowercase", normalizedSearch)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                querySnapshot = firestore.collection("publicUsers")
                    .whereEqualTo("usernameLowercase", normalizedSearch)
                    .limit(1)
                    .get()
                    .await()
            }

            if (!querySnapshot.isEmpty) {
                val friendUid = querySnapshot.documents[0].id
                firestore.collection("groups").document(groupId)
                    .update("members", FieldValue.arrayUnion(friendUid))
                    .await()
                null // Exito
            } else {
                "Usuario no encontrado con ese email o username."
            }
        } catch (e: Exception) {
            "Error al añadir usuario."
        }
    }

    suspend fun deleteGroup(groupId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        return try {
            val groupDoc = firestore.collection("groups").document(groupId).get().await()
            val adminUid = groupDoc.getString("adminUid")
            if (adminUid == currentUser.uid) {
                firestore.collection("groups").document(groupId).delete().await()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
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

    suspend fun getGroupRanking(groupId: String): List<GroupMemberRanking> {
        val currentUser = auth.currentUser ?: return emptyList()
        return try {
            val groupDoc = firestore.collection("groups").document(groupId).get().await()
            val members = groupDoc.get("members") as? List<String> ?: emptyList()
                        if (members.isEmpty()) return emptyList()

            val groupCreatedAt = groupDoc.getLong("createdAt") ?: 0L

            val calendar = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfMonth = calendar.timeInMillis

            val nextMonthCalendar = Calendar.getInstance()
            nextMonthCalendar.timeInMillis = startOfMonth
            nextMonthCalendar.add(Calendar.MONTH, 1)
            val startOfNextMonth = nextMonthCalendar.timeInMillis

            val weekCalendar = Calendar.getInstance()
            weekCalendar.set(Calendar.DAY_OF_WEEK, weekCalendar.firstDayOfWeek)
            weekCalendar.set(Calendar.HOUR_OF_DAY, 0)
            weekCalendar.set(Calendar.MINUTE, 0)
            weekCalendar.set(Calendar.SECOND, 0)
            weekCalendar.set(Calendar.MILLISECOND, 0)
            val startOfWeek = weekCalendar.timeInMillis

            val nextWeekCalendar = Calendar.getInstance()
            nextWeekCalendar.timeInMillis = startOfWeek
            nextWeekCalendar.add(Calendar.WEEK_OF_YEAR, 1)
            val startOfNextWeek = nextWeekCalendar.timeInMillis

            val rankings = members.map { uid ->
                val profileDoc = firestore.collection("publicUsers").document(uid).get().await()
                val alias = profileDoc.getString("displayName") ?: profileDoc.getString("alias") ?: "Usuario Anónimo"

                val historicalCount: Int
                val monthlyCount: Int
                val weeklyCount: Int

                if (beerRepository != null && uid == currentUser.uid) {
                    val localBeers = beerRepository.allBeers.first()
                    historicalCount = localBeers.count { it.timestamp >= groupCreatedAt }
                    monthlyCount = localBeers.count { doc ->
                        doc.timestamp >= groupCreatedAt && doc.timestamp in startOfMonth until startOfNextMonth
                    }
                    weeklyCount = localBeers.count { doc ->
                        doc.timestamp >= groupCreatedAt && doc.timestamp in startOfWeek until startOfNextWeek
                    }
                } else {
                    val userBeersSnapshot = try {
                        firestore.collection("beers")
                            .whereEqualTo("userId", uid)
                            .get()
                            .await()
                    } catch (e: Exception) {
                        android.util.Log.e("RankingDebug", "Error fetching beers for user $uid", e)
                        null
                    }

                    val userBeers = userBeersSnapshot?.documents ?: emptyList()
                    historicalCount = userBeers.count { (it.getLong("timestamp") ?: 0L) >= groupCreatedAt }
                    
                    monthlyCount = userBeers.count { doc ->
                        val timestamp = doc.getLong("timestamp") ?: 0L
                        timestamp >= groupCreatedAt && timestamp in startOfMonth until startOfNextMonth
                    }
                    weeklyCount = userBeers.count { doc ->
                        val timestamp = doc.getLong("timestamp") ?: 0L
                        timestamp >= groupCreatedAt && timestamp in startOfWeek until startOfNextWeek
                    }
                }

                GroupMemberRanking(
                    uid = uid,
                    alias = alias,
                    historicalBeers = historicalCount,
                    monthlyBeers = monthlyCount,
                    weeklyBeers = weeklyCount
                )
            }
            
            android.util.Log.d("RankingDebug", "resultado final del ranking: $rankings")
            rankings
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
                                        email = doc.getString("email") ?: "",
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
                            authorEmail = doc.getString("authorEmail") ?: "",
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
            val authorName = userProfile.getString("displayName") ?: userProfile.getString("alias") ?: "Anónimo"
            val authorUsername = userProfile.getString("username")
            
            val commentData = hashMapOf(
                "authorUid" to currentUser.uid,
                "authorName" to authorName,
                "authorEmail" to (currentUser.email ?: ""),
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
