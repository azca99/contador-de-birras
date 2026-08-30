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

    suspend fun syncProfile(alias: String) {
        val user = auth.currentUser ?: return
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "email" to user.email,
            "alias" to alias
        )
        try {
            firestore.collection("users").document(user.uid)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()
                
            val publicUserRef = firestore.collection("publicUsers").document(user.uid)
            val emailLower = user.email?.lowercase()?.trim()
            
            android.util.Log.d("SearchDebug", "Syncing Profile - UID: ${user.uid}")
            android.util.Log.d("SearchDebug", "Syncing Profile - email: ${user.email}")
            android.util.Log.d("SearchDebug", "Syncing Profile - emailLowercase: $emailLower")
            
            val publicData = hashMapOf<String, Any?>(
                "uid" to user.uid,
                "email" to user.email,
                "emailLowercase" to emailLower,
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
        val user = auth.currentUser ?: return "Debes iniciar sesión para asignar un username."
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        
        val normalizedUsername = username.trim()
        val usernameLowercase = normalizedUsername.lowercase()

        // Reglas de validación
        if (normalizedUsername.length !in 3..20) {
            return "El username debe tener entre 3 y 20 caracteres."
        }
        val regex = "^[a-zA-Z0-9_.]+$".toRegex()
        if (!regex.matches(normalizedUsername)) {
            return "Solo se permiten letras, números, puntos y guiones bajos."
        }

        try {
            // Comprobar disponibilidad
            val querySnapshot = firestore.collection("publicUsers")
                .whereEqualTo("usernameLowercase", usernameLowercase)
                .limit(1)
                .get()
                .await()

            if (!querySnapshot.isEmpty) {
                val existingDoc = querySnapshot.documents[0]
                if (existingDoc.id != user.uid) {
                    return "Ese username ya está en uso."
                }
            }

            // Guardar username
            val publicUserRef = firestore.collection("publicUsers").document(user.uid)
            val publicData = hashMapOf<String, Any?>(
                "username" to normalizedUsername,
                "usernameLowercase" to usernameLowercase,
                "usernameUpdatedAt" to com.google.firebase.Timestamp.now()
            )
            
            publicUserRef.set(publicData, com.google.firebase.firestore.SetOptions.merge()).await()
            return null // Éxito
        } catch (e: Exception) {
            e.printStackTrace()
            return "Error al verificar o guardar el username."
        }
    }
}
