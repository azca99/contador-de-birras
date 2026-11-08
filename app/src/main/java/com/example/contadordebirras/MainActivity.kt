package com.example.contadordebirras

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.UserRepository
import com.example.contadordebirras.data.dataStore
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.FriendsRepository
import com.example.contadordebirras.navigation.AppNavigation
import com.example.contadordebirras.theme.ContadorDeBirrasTheme
import com.example.contadordebirras.ui.AppViewModelFactory
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        try {
            val file = java.io.File(filesDir, "crash_log.txt")
            file.writeText(android.util.Log.getStackTraceString(e))
        } catch (ignored: Exception) {}
        kotlin.system.exitProcess(1)
    }

    val authRepository = AuthRepository(this)
    val beerDatabase = BeerDatabase.getDatabase(this)
    val syncScheduler = com.example.contadordebirras.worker.WorkManagerBeerSyncScheduler(this)
    val beerRepository = BeerRepository(beerDatabase.beerDao(), this, authRepository, syncScheduler)
    
    val activeUidFlow = authRepository.currentUser.map { it?.uid }
    val userRepository = UserRepository(this.dataStore, activeUidFlow)
    
    val friendsRepository = FriendsRepository()
    val groupsRepository = com.example.contadordebirras.domain.GroupsRepository()
    val achievementRepository = com.example.contadordebirras.data.achievements.DefaultAchievementRepository(beerDatabase.achievementDao())
    val factory = AppViewModelFactory(beerRepository, userRepository, authRepository, friendsRepository, groupsRepository, achievementRepository)

    val profileHydrator = com.example.contadordebirras.domain.ProfileHydrator(userRepository) { uid ->
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val doc = firestore.collection("publicUsers").document(uid).get().await()
            if (doc.exists()) {
                Pair(doc.getString("displayName"), doc.getString("username"))
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    lifecycleScope.launch {
        userRepository.migrateLegacy()
        authRepository.currentUser.collect { user ->
            if (user != null) {
                profileHydrator.hydrateIfActive(user.uid) { authRepository.currentUser.value?.uid }
            }
        }
    }

    if (packageName.endsWith(".c4pilot")) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val pilotEmail = "c4_pilot_user@demo-contador-c44.test"
                val pilotPassword = "password123456"
                if (auth.currentUser == null) {
                    try {
                        auth.signInWithEmailAndPassword(pilotEmail, pilotPassword).await()
                    } catch (_: Exception) {
                        auth.createUserWithEmailAndPassword(pilotEmail, pilotPassword).await()
                    }
                }
                val pilotUid = auth.currentUser?.uid
                if (!pilotUid.isNullOrBlank()) {
                    com.example.contadordebirras.sync.c4.C4SyncConfig.isEnabled = true
                    com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
                    com.example.contadordebirras.sync.c4.C4SyncStateStore.setEngineMode(
                        applicationContext,
                        pilotUid,
                        com.example.contadordebirras.sync.c4.SyncEngineMode.C4_ACTIVE
                    )
                    authRepository.updateCurrentUser()
                    userRepository.saveAlias("Piloto C4")
                    syncScheduler.requestSync(pilotUid)
                    android.util.Log.i("C4Pilot", "Piloto C4 inicializado con éxito para UID: $pilotUid en c4_debug_disposable.db")
                }
            } catch (e: Exception) {
                android.util.Log.e("C4Pilot", "Error al inicializar sesión piloto C4: ${e.message}", e)
                com.example.contadordebirras.sync.c4.C4SyncConfig.isEnabled = false
                com.example.contadordebirras.sync.c4.C4SyncConfig.engineMode = com.example.contadordebirras.sync.c4.SyncEngineMode.HALTED
            }
        }
    }

    enableEdgeToEdge()
    setContent {
      ContadorDeBirrasTheme { 
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { 
              AppNavigation(factory = factory) 
          } 
      }
    }
  }
}
