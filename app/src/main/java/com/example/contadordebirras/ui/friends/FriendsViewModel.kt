package com.example.contadordebirras.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.FriendsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FriendsViewModel(private val friendsRepository: FriendsRepository) : ViewModel() {
    val friends = friendsRepository.getFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFriend(searchInput: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val errorMsg = friendsRepository.addFriendByEmailOrUsername(searchInput)
            onResult(errorMsg)
        }
    }

    fun acceptFriend(friendshipId: String) {
        viewModelScope.launch { friendsRepository.acceptFriendRequest(friendshipId) }
    }

    fun rejectFriend(friendshipId: String) {
        viewModelScope.launch { friendsRepository.rejectFriendRequest(friendshipId) }
    }

    fun getFriendBeers(friendUid: String) = friendsRepository.getFriendBeers(friendUid)
}
