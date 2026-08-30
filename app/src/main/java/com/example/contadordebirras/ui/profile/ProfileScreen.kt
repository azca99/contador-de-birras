package com.example.contadordebirras.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.ui.graphics.Color

@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onAchievementsClick: () -> Unit) {
    val alias by viewModel.alias.collectAsState(initial = "")
    val isLocationEnabled by viewModel.isLocationEnabled.collectAsState(initial = false)
    val currentUser by viewModel.currentUser.collectAsState()

    val context = LocalContext.current
    val googleSignInClient = remember {
        val webClientIdRes = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        
        if (webClientIdRes != 0) {
            try {
                val clientId = context.getString(webClientIdRes)
                if (clientId.isNotEmpty()) {
                    gsoBuilder.requestIdToken(clientId)
                }
            } catch (e: Exception) {}
        }
        
        GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(credential).addOnCompleteListener {
                viewModel.updateCurrentUser()
            }
        } catch (e: Exception) {
            // Ignorar error
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = 0, containerColor = Color.Transparent) {
            Tab(selected = true, onClick = { }, text = { Text("Ajustes", style = MaterialTheme.typography.titleMedium) })
            Tab(selected = false, onClick = onAchievementsClick, text = { Text("Logros", style = MaterialTheme.typography.titleMedium) })
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        val username by viewModel.username.collectAsState(initial = "")
        val usernameError by viewModel.usernameError.collectAsState()
        
        var localAlias by remember { mutableStateOf<String?>(null) }
        val displayAlias = localAlias ?: alias

        var localUsername by remember { mutableStateOf<String?>(null) }
        val displayUsername = localUsername ?: username

        OutlinedTextField(
            value = displayAlias,
            onValueChange = { 
                localAlias = it
                viewModel.setAlias(it) 
            },
            label = { Text("Alias") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = displayUsername,
            onValueChange = { localUsername = it },
            label = { Text("Username") },
            isError = usernameError != null,
            modifier = Modifier.fillMaxWidth()
        )
        
        if (usernameError != null) {
            Text(text = usernameError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { 
                if (localUsername != null) {
                    viewModel.setUsername(localUsername!!) {
                        // Success handling if needed
                    }
                }
            },
            enabled = localUsername != null && localUsername != username,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End)
        ) {
            Text("Guardar Username")
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Activar ubicación al registrar")
            Switch(
                checked = isLocationEnabled,
                onCheckedChange = { viewModel.setLocationEnabled(it) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text("Sincronización en la Nube", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (currentUser != null) {
            Text("Conectado como: ${currentUser?.email}")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.signOut() }) {
                Text("Cerrar Sesión")
            }
        } else {
            Text("Inicia sesión para sincronizar tus cervezas.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { launcher.launch(googleSignInClient.signInIntent) }) {
                Text("Iniciar Sesión con Google")
            }
        }
    }
}
