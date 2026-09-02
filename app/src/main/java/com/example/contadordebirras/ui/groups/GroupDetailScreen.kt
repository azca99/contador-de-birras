package com.example.contadordebirras.ui.groups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.contadordebirras.domain.GroupMemberRanking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(groupId: String, viewModel: GroupDetailViewModel, onBack: () -> Unit) {
    val rankings by viewModel.rankings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    var selectedTabIndex by remember { mutableStateOf(0) } // 0 = Historical, 1 = Monthly
    
    val currentUserUid = remember { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var addResultMsg by remember { mutableStateOf<String?>(null) }

    val members by viewModel.members.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val friends by viewModel.friends.collectAsState()
    
    var selectedMainTab by remember { mutableStateOf(0) } // 0 = Ranking, 1 = Participantes, 2 = Comentarios
    var commentInput by remember { mutableStateOf("") }
    var isSendingComment by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        viewModel.loadRankings(groupId)
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            if (selectedMainTab != 2) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = "Añadir Miembro")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Detalle del Grupo", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(16.dp))

            TabRow(selectedTabIndex = selectedMainTab) {
                Tab(selected = selectedMainTab == 0, onClick = { selectedMainTab = 0 }, text = { Text("Ranking") })
                Tab(selected = selectedMainTab == 1, onClick = { selectedMainTab = 1 }, text = { Text("Participantes") })
                Tab(selected = selectedMainTab == 2, onClick = { selectedMainTab = 2 }, text = { Text("Comentarios") })
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (selectedMainTab == 0) {
                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("Histórico") })
                    Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Mensual") })
                    Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("Semanal") })
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val sortedRankings = when (selectedTabIndex) {
                        0 -> rankings.sortedByDescending { it.historicalBeers }
                        1 -> rankings.sortedByDescending { it.monthlyBeers }
                        else -> rankings.sortedByDescending { it.weeklyBeers }
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(sortedRankings) { index, member ->
                            val count = when (selectedTabIndex) {
                                0 -> member.historicalBeers
                                1 -> member.monthlyBeers
                                else -> member.weeklyBeers
                            }
                            
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${index + 1}", 
                                        style = MaterialTheme.typography.headlineMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = member.alias, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(
                                        text = "$count 🍺", 
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (selectedMainTab == 1) {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(members) { _, member ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.displayName, style = MaterialTheme.typography.titleMedium)
                                    if (!member.username.isNullOrEmpty()) {
                                        Text(text = "@${member.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = {
                                currentUserUid?.let { uid ->
                                    viewModel.removeMember(groupId, uid) { success ->
                                        if (success) onBack()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Rounded.ExitToApp, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Abandonar Grupo")
                        }
                    }
                }
            } else if (selectedMainTab == 2) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(comments) { _, comment ->
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val authorDisplay = comment.authorUsername?.let { "@$it" } ?: comment.authorName
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = authorDisplay, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val date = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(comment.createdAt))
                                        Text(text = date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = comment.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = commentInput,
                            onValueChange = { if (it.length <= 300) commentInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Escribe un comentario...") },
                            maxLines = 3,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (commentInput.isNotBlank()) {
                                    isSendingComment = true
                                    viewModel.sendComment(groupId, commentInput) { success ->
                                        isSendingComment = false
                                        if (success) commentInput = ""
                                    }
                                }
                            },
                            enabled = commentInput.isNotBlank() && !isSendingComment
                        ) {
                            Text("Enviar")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                emailInput = ""
                addResultMsg = null
            },
            title = { Text("Añadir al Grupo") },
            text = {
                Column {
                    Text("Introduce el email o el username de un amigo.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email o Username") },
                        singleLine = true
                    )
                    if (addResultMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(addResultMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (emailInput.isNotBlank()) {
                        viewModel.addMember(groupId, emailInput) { errorMsg ->
                            if (errorMsg == null) {
                                showAddDialog = false
                                emailInput = ""
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
                    emailInput = ""
                    addResultMsg = null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
