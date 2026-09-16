package com.example.contadordebirras.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.contadordebirras.ui.components.SecureFirebaseImage
import com.example.contadordebirras.data.BeerEntity
import com.example.contadordebirras.domain.BeerType
import com.example.contadordebirras.ui.stats.StatsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: StatsViewModel, onStatsClick: () -> Unit) {
    val beers by viewModel.allBeers.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val groupedBeers = remember(beers) {
        val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val yesterdayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86400000))
        beers.groupBy { beer ->
            val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(beer.timestamp))
            when (dateStr) {
                todayStr -> "HOY"
                yesterdayStr -> "AYER"
                else -> dateStr
            }
        }
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var beerToEdit by remember { mutableStateOf<BeerEntity?>(null) }
    var editComment by remember { mutableStateOf("") }
    var editType by remember { mutableStateOf(BeerType.CANA) }
    var expandedDropdown by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf(0) }
    var viewedBeer by remember { mutableStateOf<BeerEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tu Diario", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onStatsClick) {
                Icon(Icons.Rounded.BarChart, contentDescription = "Estadísticas", tint = MaterialTheme.colorScheme.primary)
            }
        }
        
        TabRow(
            selectedTabIndex = selectedMode,
            containerColor = Color.Transparent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedMode]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            Tab(
                selected = selectedMode == 0,
                onClick = { selectedMode = 0 },
                text = { Text("Diario", style = MaterialTheme.typography.titleMedium, color = if (selectedMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
            Tab(
                selected = selectedMode == 1,
                onClick = { selectedMode = 1 },
                text = { Text("Fotos", style = MaterialTheme.typography.titleMedium, color = if (selectedMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (selectedMode == 0) {
            if (beers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.List, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Aún no hay birras", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("Tu diario está vacío.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    groupedBeers.forEach { (dateHeader, beersInDate) ->
                        item {
                            Text(
                                text = dateHeader, 
                                style = MaterialTheme.typography.labelLarge, 
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(beersInDate) { beer ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    val photoToLoad = beer.photoUri ?: beer.remotePhotoUrl
                                    if (photoToLoad != null) {
                                        SecureFirebaseImage(
                                            model = photoToLoad,
                                            contentDescription = "Foto",
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { viewedBeer = beer },
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                    }
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text(text = beer.type.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                            Text(text = timeFormat.format(Date(beer.timestamp)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        
                                        val loc = beer.locationName ?: if (beer.latitude != null) "Ubicación registrada" else null
                                        if (loc != null) {
                                            Text(text = loc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        
                                        if (!beer.comment.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(text = beer.comment, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                    
                                    Column {
                                        IconButton(onClick = {
                                            beerToEdit = beer
                                            editComment = beer.comment ?: ""
                                            editType = beer.type
                                            showEditDialog = true
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { viewModel.deleteBeer(beer) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val beersWithPhotos = beers.filter { it.photoUri != null || it.remotePhotoUrl != null }
            if (beersWithPhotos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Todavía no tienes fotos", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text("Las birras a las que añadas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("una foto aparecerán aquí.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(beersWithPhotos) { beer ->
                        val photoToLoad = beer.photoUri ?: beer.remotePhotoUrl
                        if (photoToLoad != null) {
                            SecureFirebaseImage(
                                model = photoToLoad,
                                contentDescription = "Foto de ${beer.type.displayName} del ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(beer.timestamp))}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { viewedBeer = beer },
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

    if (viewedBeer != null) {
        BeerPhotoViewer(beer = viewedBeer!!) {
            viewedBeer = null
        }
    }
}

@Composable
fun BeerPhotoViewer(beer: BeerEntity, onDismiss: () -> Unit) {
    val photoToLoad = beer.photoUri ?: beer.remotePhotoUrl ?: return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable { onDismiss() } // tap outside
        ) {
            SecureFirebaseImage(
                model = photoToLoad,
                contentDescription = "Foto de ${beer.type.displayName} del ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(beer.timestamp))}",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                contentScale = ContentScale.Fit
            )
            
            // close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Cerrar foto", tint = Color.White)
            }
            
            // text info at the bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
            ) {
                Text(text = beer.type.displayName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                val loc = beer.locationName ?: if (beer.latitude != null) "Ubicación registrada" else null
                if (loc != null) {
                    Text(text = loc, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                }
                if (!beer.comment.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = beer.comment, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                Text(
                    text = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(beer.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }
    }
}
