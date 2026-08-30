package com.example.contadordebirras.ui.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.contadordebirras.data.FriendProfile
import androidx.compose.material.icons.rounded.Groups

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    viewModel: FriendsViewModel,
    onFriendClick: (String, String) -> Unit,
    onGroupsClick: () -> Unit
) {
    val friends by viewModel.friends.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var addResultMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Añadir amigo")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(onClick = onGroupsClick, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text("Grupos")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text("Amigos")
                }
            }

            if (friends.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aún no has añadido a ningún amigo.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(friends) { friend ->
                        FriendItem(friend, onClick = { onFriendClick(friend.uid, friend.alias.ifEmpty { friend.email }) })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                addResultMsg = null
            },
            title = { Text("Añadir Amigo") },
            text = {
                Column {
                    Text("Introduce el correo o username exacto de tu amigo:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        label = { Text("Email o Username") },
                        singleLine = true
                    )
                    if (addResultMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(addResultMsg!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (searchInput.isNotBlank()) {
                        viewModel.addFriend(searchInput) { errorMsg ->
                            if (errorMsg == null) {
                                showAddDialog = false
                                searchInput = ""
                                addResultMsg = null
                            } else {
                                addResultMsg = errorMsg
                            }
                        }
                    }
                }) {
                    Text("Añadir")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    addResultMsg = null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun FriendItem(friend: FriendProfile, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = friend.alias.ifEmpty { "Sin alias" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = friend.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
