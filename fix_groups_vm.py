import re

with open('app/src/main/java/com/example/contadordebirras/ui/groups/GroupsViewModel.kt', 'r', encoding='latin-1') as f:
    content = f.read()

methods = '''
    fun acceptFriendRequest(friendshipId: String) {
        viewModelScope.launch { friendsRepository.acceptFriendRequest(friendshipId) }
    }
    
    fun rejectFriendRequest(friendshipId: String) {
        viewModelScope.launch { friendsRepository.rejectFriendRequest(friendshipId) }
    }
'''

content = content.replace(
    'fun addFriendFromGroup(uid: String, onResult: (String?) -> Unit) {',
    methods + '\n    fun addFriendFromGroup(uid: String, onResult: (String?) -> Unit) {'
)

with open('app/src/main/java/com/example/contadordebirras/ui/groups/GroupsViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content)
