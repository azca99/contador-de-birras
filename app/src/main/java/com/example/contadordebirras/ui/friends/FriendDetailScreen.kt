package com.example.contadordebirras.ui.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.contadordebirras.data.BeerEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendDetailScreen(
    viewModel: FriendsViewModel,
    friendUid: String,
    friendAlias: String,
    onBack: () -> Unit
) {
    val beers by remember { viewModel.getFriendBeers(friendUid) }.collectAsState(initial = emptyList())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(friendAlias) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Cervezas", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = beers.size.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text("Historial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            if (beers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Este amigo aún no ha registrado cervezas.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(beers) { beer ->
                        FriendBeerCard(beer, dateFormat)
                    }
                }
            }
        }
    }
}

@Composable
fun FriendBeerCard(beer: BeerEntity, dateFormat: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
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

            val imageUrl = beer.remotePhotoUrl ?: beer.photoUri
            if (imageUrl != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AsyncImage(
                    model = imageUrl,
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
