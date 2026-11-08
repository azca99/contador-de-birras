package com.example.contadordebirras.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.contadordebirras.domain.BeerType

/**
 * Pantalla de control y pruebas interactivas de sincronización Protocolo C4 (Fase C4.4B).
 *
 * Exclusivamente accesible en compilaciones DEBUG. Permite interactuar con la base
 * Room descartable y los emuladores Firebase para demostrar y verificar:
 * 1. Creación con registro previo de mutación durable.
 * 2. Edición parcial de comentario con invalidación causal de mutación previa.
 * 3. Borrado causal (tombstone).
 * 4. PUSH y PULL manual.
 * 5. Inspección del historial durable de mutaciones en tiempo real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun C4SyncDebugScreen(
    viewModel: C4SyncDebugViewModel,
    onBack: () -> Unit
) {
    val isC4Active by viewModel.isC4Active
    val statusMessage by viewModel.statusMessage
    val beers = viewModel.beersList
    val history = viewModel.mutationHistory
    val logs = viewModel.executionLogs

    var newBeerComment by remember { mutableStateOf("") }
    var selectedBeerType by remember { mutableStateOf<BeerType>(BeerType.CANA) }
    var editingSyncId by remember { mutableStateOf<String?>(null) }
    var editedCommentText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔬 Protocolo C4 Debug", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Interruptor de activación y Banner de estado
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Motor C4 Aislado",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isC4Active) "Activo (Base Descartable + Emulator)" else "Inactivo (Legacy Activo)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isC4Active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = isC4Active,
                                onCheckedChange = { viewModel.toggleC4(it) }
                            )
                        }

                        if (!isC4Active) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = viewModel.authEmail.value,
                                onValueChange = { viewModel.authEmail.value = it },
                                label = { Text("Email Test Auth (Auth Emulator)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cuenta: ${viewModel.authEmail.value}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isC4Active) {
                // 2. Panel de Creación de Cervezas
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("1. Crear Cerveza en Base Descartable", fontWeight = FontWeight.Bold)
                            
                            OutlinedTextField(
                                value = newBeerComment,
                                onValueChange = { newBeerComment = it },
                                label = { Text("Comentario (ej: IPA artesanal)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Tipo: ${selectedBeerType.name}", style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = {
                                        viewModel.createBeer(
                                            type = selectedBeerType,
                                            comment = newBeerComment.ifBlank { "Cerveza C4 de prueba" }
                                        )
                                        newBeerComment = ""
                                    }
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Crear")
                                }
                            }
                        }
                    }
                }

                // 3. Acciones PUSH, PULL y Limpieza
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.executePush() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PUSH Sync")
                            }

                            Button(
                                onClick = { viewModel.executePull() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PULL (Descubrir)")
                            }
                        }

                        Button(
                            onClick = { viewModel.triggerWorkManagerSync() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sincronizar en Segundo Plano (WorkManager)")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { viewModel.clearLocalDatabase() },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Limpiar Base Room Local")
                            }
                        }
                    }
                }

                // 4. Panel de Transición y Staging Cutover (Fase C4.5)
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Transición Legacy a C4 (Fase C4.5)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Prueba de migración idempotente sin pérdida de datos en entorno aislado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.seedLegacyScenario() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Text("1. Sembrar Legacy", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { viewModel.executeCutover() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("2. Ejecutar Cutover", fontSize = 12.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { viewModel.rollbackToLegacy() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("3. Revertir a Legacy (Rollback)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // 5. Lista de Cervezas Locales
                item {
                    Text(
                        text = "Cervezas en Base Descartable (${beers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (beers.isEmpty()) {
                    item {
                        Text(
                            "No hay cervezas registradas en la base descartable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    items(beers) { beer ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${beer.type.name} (syncId: ${beer.syncId.take(8)}...)",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = beer.syncStatus,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (beer.syncStatus == "SYNCED") Color(0xFF2E7D32) else Color(0xFFE65100)
                                    )
                                }

                                Text(
                                    text = "Rev Remota: ${beer.remoteRevision} | BaseRev: ${beer.baseRevision}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Comentario: \"${beer.comment.orEmpty()}\"",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "LastMutationId: ${beer.lastMutationId?.take(8) ?: "none"}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        editingSyncId = beer.syncId
                                        editedCommentText = (beer.comment.orEmpty().ifBlank { "Birra" }) + " (Editada a Rev 2)"
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Editar comentario", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.deleteBeer(beer.syncId) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Borrar", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Historial Durable de Mutaciones
                item {
                    Text(
                        text = "Historial Durable Room (local_mutation_history) (${history.size} pendientes)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (history.isEmpty()) {
                    item {
                        Text(
                            "No hay mutaciones pendientes en el historial durable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    items(history) { mut ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Mutación: ${mut.mutationId.take(8)}... | Estado: ${mut.status}",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "SyncId: ${mut.syncId.take(8)}... | BaseRev: ${mut.baseRevision}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "DirtyFields: ${mut.dirtyFields}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // 6. Log de Operaciones
                item {
                    Text(
                        text = "Log de Sincronización en Tiempo Real",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(logs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = Color(0xFF00FF66),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo para editar comentario
    if (editingSyncId != null) {
        AlertDialog(
            onDismissRequest = { editingSyncId = null },
            title = { Text("Editar Comentario (C4)") },
            text = {
                OutlinedTextField(
                    value = editedCommentText,
                    onValueChange = { editedCommentText = it },
                    label = { Text("Nuevo comentario") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    editingSyncId?.let { syncId ->
                        viewModel.editBeerComment(syncId, editedCommentText)
                    }
                    editingSyncId = null
                }) {
                    Text("Guardar Mutación")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSyncId = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
