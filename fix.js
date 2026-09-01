const fs = require("fs");
let content = fs.readFileSync("app/src/main/java/com/example/contadordebirras/ui/groups/GroupsViewModel.kt", "utf8");

const replacement = `class GroupsViewModel(private val groupsRepository: GroupsRepository) : ViewModel() {
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

    fun createGroup(name: String, onResult: (Boolean) -> Unit) {`;

content = content.replace(/class GroupsViewModel\(private val groupsRepository: GroupsRepository\) : ViewModel\(\) \{\s*val groups = groupsRepository\.getGroups\(\)\s*\.stateIn\(viewModelScope, SharingStarted\.WhileSubscribed\(5000\), emptyList\(\)\)\s*fun createGroup\(name: String, onResult: \(Boolean\) -> Unit\) \{/m, replacement);

fs.writeFileSync("app/src/main/java/com/example/contadordebirras/ui/groups/GroupsViewModel.kt", content);
console.log("Fixed");

