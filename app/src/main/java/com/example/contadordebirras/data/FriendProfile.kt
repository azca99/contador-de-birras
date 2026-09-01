package com.example.contadordebirras.data

data class FriendProfile(
    val uid: String = "",
    val email: String = "", // Deprecated/Removed, kept for compatibility if needed
    val alias: String = "",
    val photoUrl: String? = null,
    val status: String = "ACCEPTED",
    val requester: String = "",
    val friendshipId: String = ""
)
