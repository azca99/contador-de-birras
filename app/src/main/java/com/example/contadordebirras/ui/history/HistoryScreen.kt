package com.example.contadordebirras.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.contadordebirras.ui.components.SecureFirebaseImage
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.ui.stats.StatsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


import androidx.compose.material.icons.rounded.BarChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: StatsViewModel, onStatsClick: () -> Unit) {
    val beers by viewModel.allBeers.collectAsState()
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())


    var showEditDialog by remember { mutableStateOf(false) }
    var beerToEdit by remember { mutableStateOf<BeerEntity?>(null) }
    var editComment by remember { mutableStateOf("") }
    var editType by remember { mutableStateOf(BeerType.CANA) }
    var expandedDropdown by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Historial", style = MaterialTheme.typography.headlineLarge)
            IconButton(onClick = onStatsClick) {
                Icon(Icons.Rounded.BarChart, contentDescription = "Estadísticas")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(beers) { beer ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Tipo: ${beer.type.displayName}", style = MaterialTheme.typography.titleMedium)
                                Text(text = "Fecha: ${dateFormat.format(Date(beer.timestamp))}", style = MaterialTheme.typography.bodySmall)
                                
                                if (beer.locationName != null) {
                                    Text(text = "📍 ${beer.locationName}", style = MaterialTheme.typography.bodySmall)
                                } else if (beer.latitude != null && beer.longitude != null) {
                                    Text(text = "📍 Ubicación registrada", style = MaterialTheme.typography.bodySmall)
                                }
                                if (!beer.comment.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = "💬 ${beer.comment}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            Row {
                                IconButton(onClick = {
                                    beerToEdit = beer
                                    editComment = beer.comment ?: ""
                                    editType = beer.type
                                    showEditDialog = true
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = { viewModel.deleteBeer(beer) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (beer.photoUri != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SecureFirebaseImage(
                                model = beer.photoUri,
                                contentDescription = "Foto de cerveza",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && beerToEdit != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar Registro") },
            text = {
                Column {
                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown,
                        onExpandedChange = { expandedDropdown = !expandedDropdown }
                    ) {
                        OutlinedTextField(
                            value = editType.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Tipo de Cerveza") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            BeerType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = {
                                        editType = type
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editComment,
                        onValueChange = { editComment = it },
                        label = { Text("Comentario") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateBeer(beerToEdit!!.copy(type = editType, comment = editComment))
                    showEditDialog = false
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
