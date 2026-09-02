package com.example.contadordebirras.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contadordebirras.domain.GroupMemberRanking
import com.example.contadordebirras.domain.GroupsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(private val groupsRepository: GroupsRepository) : ViewModel() {
    val groups = groupsRepository.getGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val pendingInvitations = groupsRepository.getPendingInvitations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    fun acceptInvitation(invitation: com.example.contadordebirras.domain.GroupInvitationEntity) {
        viewModelScope.launch { groupsRepository.acceptInvitation(invitation) }
    }
    
    fun rejectInvitation(invitation: com.example.contadordebirras.domain.GroupInvitationEntity) {
        viewModelScope.launch { groupsRepository.rejectInvitation(invitation) }
    }

    fun createGroup(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = groupsRepository.createGroup(name)
            onResult(success)
        }
    }
}

class GroupDetailViewModel(private val groupsRepository: GroupsRepository, private val friendsRepository: com.example.contadordebirras.domain.FriendsRepository) : ViewModel() {
    private val _rankings = MutableStateFlow<List<GroupMemberRanking>>(emptyList())
    val rankings: StateFlow<List<GroupMemberRanking>> = _rankings.asStateFlow()

    private val _members = MutableStateFlow<List<com.example.contadordebirras.domain.GroupMemberDetail>>(emptyList())
    val members: StateFlow<List<com.example.contadordebirras.domain.GroupMemberDetail>> = _members.asStateFlow()

    private val _comments = MutableStateFlow<List<com.example.contadordebirras.domain.GroupComment>>(emptyList())
    val comments: StateFlow<List<com.example.contadordebirras.domain.GroupComment>> = _comments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var membersJob: kotlinx.coroutines.Job? = null
    private var commentsJob: kotlinx.coroutines.Job? = null

    fun loadRankings(groupId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val fetchedRankings = groupsRepository.getGroupRanking(groupId)
            _rankings.value = fetchedRankings
            _isLoading.value = false
        }
        
        membersJob?.cancel()
        membersJob = viewModelScope.launch {
            groupsRepository.getGroupMembers(groupId).collect { membersList ->
                _members.value = membersList
            }
        }
        
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            groupsRepository.getGroupComments(groupId).collect { commentsList ->
                _comments.value = commentsList
            }
        }
    }

    fun addMember(groupId: String, searchQuery: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val errorMsg = groupsRepository.addMemberByEmailOrUsername(groupId, searchQuery)
            if (errorMsg == null) {
                loadRankings(groupId)
            }
            onResult(errorMsg)
        }
    }
    
    fun removeMember(groupId: String, memberUid: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = groupsRepository.removeMemberFromGroup(groupId, memberUid)
            if (success) {
                loadRankings(groupId)
            }
            onResult(success)
        }
    }
    
    val friends = friendsRepository.getFriends().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    
    fun acceptFriendRequest(friendshipId: String) {
        viewModelScope.launch { friendsRepository.acceptFriendRequest(friendshipId) }
    }
    
    fun rejectFriendRequest(friendshipId: String) {
        viewModelScope.launch { friendsRepository.rejectFriendRequest(friendshipId) }
    }

    fun addFriendFromGroup(uid: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val res = friendsRepository.addFriendByUid(uid)
            onResult(res)
        }
    }

    fun sendComment(groupId: String, text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = groupsRepository.addComment(groupId, text)
            onResult(success)
        }
    }
}
