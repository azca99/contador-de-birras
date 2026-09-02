package com.example.contadordebirras.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.example.contadordebirras.domain.BeerType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import com.example.contadordebirras.ui.components.SecureFirebaseImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.contadordebirras.utils.ImageUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val totalCount by viewModel.totalCount.collectAsState()
    val lastBeer by viewModel.lastBeer.collectAsState()
    var selectedType by remember { mutableStateOf(BeerType.CANA) }
    var expanded by remember { mutableStateOf(false) }
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val userAlias by viewModel.userAlias.collectAsState()
    val isSavingBeer by viewModel.isSavingBeer.collectAsState()
    var comment by remember { mutableStateOf("") }
    var photoSource by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf(false) }
    var photoUri by remember { mutableStateOf<String?>(null) }
    var photoSource by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val compressedUri = ImageUtils.compressAndSaveImage(context, uri)
            photoUri = compressedUri?.toString()
            photoSource = "GALLERY"
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            val compressedUri = ImageUtils.compressAndSaveImage(context, tempCameraUri!!)
            photoUri = compressedUri?.toString()
            photoSource = "CAMERA"
        } else {
            Toast.makeText(context, "No se pudo hacer la foto", Toast.LENGTH_SHORT).show()
        }
    }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (pendingSave) {
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                val locationFetcher: suspend () -> Pair<Double?, Double?> = {
                    val tokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                    val loc = kotlinx.coroutines.tasks.await(
                        fusedLocationClient.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 
                            tokenSource.token
                        )
                    )
                    if (loc != null) {
                        Pair(loc.latitude, loc.longitude)
                    } else {
                        val lastLoc = kotlinx.coroutines.tasks.await(fusedLocationClient.lastLocation)
                        if (lastLoc != null) Pair(lastLoc.latitude, lastLoc.longitude) else Pair(null, null)
                    }
                }
                viewModel.executeSave(selectedType, photoUri, comment, photoSource, locationFetcher) {
                    photoUri = null; comment = ""; photoSource = null
                    pendingSave = false
                }
            } else {
                viewModel.executeSave(selectedType, photoUri, comment, photoSource, null) {
                    photoUri = null; comment = ""; photoSource = null
                    pendingSave = false
                }
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "🍻 Bar de $userAlias", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(text = "Total Servidas", style = MaterialTheme.typography.titleMedium)
        
        AnimatedContent(
            targetState = totalCount,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInVertically { height -> height } + fadeIn()).togetherWith(slideOutVertically { height -> -height } + fadeOut())
                } else {
                    (slideInVertically { height -> -height } + fadeIn()).togetherWith(slideOutVertically { height -> height } + fadeOut())
                }
            },
            label = "countAnimation"
        ) { targetCount ->
            Text(text = "$targetCount", style = MaterialTheme.typography.displayLarge)
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedType.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Tipo de Cerveza") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(16.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                BeerType.values().forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName) },
                        onClick = {
                            selectedType = type
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text("Añadir comentario...") },
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(0.8f)) {
            Button(onClick = { showImageDialog = true }) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Add Photo")
                Spacer(Modifier.width(8.dp))
                Text("Añadir foto")
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (photoUri != null) {
                Box {
                    SecureFirebaseImage(
                        model = photoUri,
                        contentDescription = "Miniatura",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    IconButton(
                        onClick = { photoUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .padding(2.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showImageDialog) {
            AlertDialog(
                onDismissRequest = { showImageDialog = false },
                title = { Text("Añadir foto") },
                text = { Text("Elige una opción para añadir una foto a tu birra. ") },
                confirmButton = {
                    TextButton(onClick = {
                        showImageDialog = false
                        tempCameraUri = ImageUtils.createTempCameraUri(context)
                        takePicture.launch(tempCameraUri!!)
                    }) {
                        Text("Hacer foto")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showImageDialog = false
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text("Elegir de galería")
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(targetValue = if (isPressed) 0.9f else 1f, label = "buttonScale")

        ElevatedButton(
            onClick = {
                if (isSavingBeer || pendingSave) return@ElevatedButton
                pendingSave = true
                if (locationEnabled) {
                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                    if (hasFine || hasCoarse) {
                        val locationFetcher: suspend () -> Pair<Double?, Double?> = {
                            val tokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                            val loc = kotlinx.coroutines.tasks.await(
                                fusedLocationClient.getCurrentLocation(
                                    com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 
                                    tokenSource.token
                                )
                            )
                            if (loc != null) {
                                Pair(loc.latitude, loc.longitude)
                            } else {
                                val lastLoc = kotlinx.coroutines.tasks.await(fusedLocationClient.lastLocation)
                                if (lastLoc != null) Pair(lastLoc.latitude, lastLoc.longitude) else Pair(null, null)
                            }
                        }
                        viewModel.executeSave(selectedType, photoUri, comment, photoSource, locationFetcher) {
                            photoUri = null; comment = ""; photoSource = null
                            pendingSave = false
                        }
                    } else {
                        requestPermissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                } else {
                    viewModel.executeSave(selectedType, photoUri, comment, photoSource, null) {
                        photoUri = null; comment = ""; photoSource = null
                        pendingSave = false
                    }
                }
            },
            interactionSource = interactionSource,
            modifier = Modifier.size(200.dp).graphicsLayer(scaleX = scale, scaleY = scale),
            elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 12.dp, pressedElevation = 4.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFFFDF7E3), // Coaster cardboard color
                contentColor = androidx.compose.ui.graphics.Color(0xFFA6192E) // Deep Spanish red
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center, 
                modifier = Modifier
                    .fillMaxSize()
                    .border(6.dp, androidx.compose.ui.graphics.Color(0xFFA6192E), androidx.compose.foundation.shape.CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.82f)
                        .border(2.dp, androidx.compose.ui.graphics.Color(0xFFA6192E).copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.75f)
                        .border(1.dp, androidx.compose.ui.graphics.Color(0xFFA6192E).copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "+1", style = MaterialTheme.typography.displayLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Text(text = "BIRRA", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (lastBeer != null) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${lastBeer!!.type.displayName} a las ${dateFormat.format(Date(lastBeer!!.timestamp))}", 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { 
                            viewModel.undoLastBeer { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }, 
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Deshacer última birra")
                    }
                }
            }
        }
    }
}
