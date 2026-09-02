import re

with open('app/src/main/java/com/example/contadordebirras/ui/groups/GroupDetailScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

target_row = '''Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.displayName, style = MaterialTheme.typography.titleMedium)
                                    if (!member.username.isNullOrEmpty()) {
                                        Text(text = "@${member.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }'''

new_row = '''Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.displayName, style = MaterialTheme.typography.titleMedium)
                                    if (!member.username.isNullOrEmpty()) {
                                        Text(text = "@${member.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                
                                if (member.uid != currentUserUid) {
                                    val friendProfile = friends.find { it.uid == member.uid }
                                    when (friendProfile?.status) {
                                        "ACCEPTED" -> {
                                            Text("Amigos", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        }
                                        "PENDING" -> {
                                            if (friendProfile.requester == currentUserUid) {
                                                Text("Solicitud enviada", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                                            } else {
                                                Row {
                                                    TextButton(onClick = { viewModel.rejectFriendRequest(friendProfile.friendshipId) }) { Text("Rechazar") }
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Button(onClick = { viewModel.acceptFriendRequest(friendProfile.friendshipId) }) { Text("Aceptar") }
                                                }
                                            }
                                        }
                                        else -> {
                                            OutlinedButton(onClick = { viewModel.addFriendFromGroup(member.uid) {} }) {
                                                Text("A\u00f1adir amigo")
                                            }
                                        }
                                    }
                                }
                            }'''

if target_row in content:
    content = content.replace(target_row, new_row)
else:
    print("TARGET ROW NOT FOUND!")

with open('app/src/main/java/com/example/contadordebirras/ui/groups/GroupDetailScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
