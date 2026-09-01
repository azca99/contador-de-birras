package com.example.contadordebirras.domain

data class GroupEntity(
    val id: String = "",
    val name: String = "",
    val adminUid: String = "",
    val members: List<String> = emptyList(),
    val createdAt: Long = 0L
)

data class GroupMemberRanking(
    val uid: String,
    val alias: String,
    val historicalBeers: Int,
    val monthlyBeers: Int,
    val weeklyBeers: Int
)

data class GroupMemberDetail(
    val uid: String,
    val displayName: String,
    val username: String?,
    val photoUrl: String?
)

data class GroupComment(
    val commentId: String,
    val authorUid: String,
    val authorName: String,
    val authorUsername: String?,
    val text: String,
    val createdAt: Long
)

