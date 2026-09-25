package com.example.contadordebirras.domain

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(private val context: Context) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }

    fun getGoogleSignInClient(): GoogleSignInClient {
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
        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    fun updateCurrentUser() {
        _currentUser.value = auth.currentUser
    }

    fun signOut() {
        auth.signOut()
        getGoogleSignInClient().signOut()
        _currentUser.value = null
    }

    suspend fun syncProfile(alias: String, syncUid: String? = null) {
        val user = auth.currentUser ?: return
        if (syncUid != null && user.uid != syncUid) return
        
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "email" to user.email,
            "alias" to alias
        )
        try {
            firestore.collection("users").document(user.uid)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()
                
            val userRef = firestore.collection("users").document(user.uid)
            val emailLower = user.email?.lowercase()?.trim()
            val privateData = hashMapOf<String, Any?>(
                "email" to user.email,
                "emailLowercase" to emailLower
            )
            userRef.set(privateData, com.google.firebase.firestore.SetOptions.merge()).await()

            val publicUserRef = firestore.collection("publicUsers").document(user.uid)
            val publicData = hashMapOf<String, Any?>(
                "uid" to user.uid,
                "displayName" to alias,
                "photoUrl" to user.photoUrl?.toString(),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )
            
            val doc = publicUserRef.get().await()
            if (!doc.exists()) {
                publicData["createdAt"] = com.google.firebase.Timestamp.now()
            }
            
            publicUserRef.set(publicData, com.google.firebase.firestore.SetOptions.merge()).await()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setUsername(username: String): String? {
        auth.currentUser ?: return "Debes iniciar sesión para asignar un username."
        
        val normalizedUsername = username.trim()


        // Reglas de validación
        if (normalizedUsername.length !in 3..20) {
            return "El username debe tener entre 3 y 20 caracteres."
        }
        val regex = "^[a-zA-Z0-9_.]+$".toRegex()
        if (!regex.matches(normalizedUsername)) {
            return "Solo se permiten letras, números, puntos y guiones bajos."
        }

        try {
            val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
            val data = hashMapOf("username" to normalizedUsername)
            
            functions.getHttpsCallable("setUsername")
                .call(data)
                .await()
                
            return null // Éxito
        } catch (e: Exception) {
            val isDebug = com.example.contadordebirras.BuildConfig.DEBUG
            if (isDebug) {
                android.util.Log.e("AuthRepository", "Error en setUsername: ${e.message}")
            }
            if (e is com.google.firebase.functions.FirebaseFunctionsException) {
                when (e.code) {
                    com.google.firebase.functions.FirebaseFunctionsException.Code.ALREADY_EXISTS -> {
                        return "Ese username ya está en uso."
                    }
                    com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT -> {
                        return e.message ?: "Username inválido."
                    }
                    com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED -> {
                        return "Sesión inválida. Por favor, inicia sesión de nuevo e inténtalo."
                    }
                    com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE -> {
                        return "No se pudo guardar el username. Comprueba tu conexión e inténtalo de nuevo."
                    }
                    else -> {
                        return "Error al verificar o guardar el username."
                    }
                }
            }
            return "No se pudo guardar el username. Comprueba tu conexión e inténtalo de nuevo."
        }
    }
}
