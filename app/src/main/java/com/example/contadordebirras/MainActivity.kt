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
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    FirebaseApp.initializeApp(this)
    val firebaseAppCheck = FirebaseAppCheck.getInstance()
    firebaseAppCheck.installAppCheckProviderFactory(
        if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance() else PlayIntegrityAppCheckProviderFactory.getInstance()
    )
    
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        try {
            val file = java.io.File(filesDir, "crash_log.txt")
            file.writeText(android.util.Log.getStackTraceString(e))
        } catch (ignored: Exception) {}
        kotlin.system.exitProcess(1)
    }

    val authRepository = AuthRepository(this)
    val beerDatabase = BeerDatabase.getDatabase(this)
    val beerRepository = BeerRepository(beerDatabase.beerDao(), this, authRepository)
    
    val activeUidFlow = authRepository.currentUser.map { it?.uid }
    val userRepository = UserRepository(this.dataStore, activeUidFlow)
    
    val friendsRepository = FriendsRepository()
    val groupsRepository = com.example.contadordebirras.domain.GroupsRepository()
    val achievementRepository = com.example.contadordebirras.data.achievements.DefaultAchievementRepository(beerDatabase.achievementDao())
    val factory = AppViewModelFactory(beerRepository, userRepository, authRepository, friendsRepository, groupsRepository, achievementRepository)

    lifecycleScope.launch {
        userRepository.migrateLegacy()
        authRepository.currentUser.collect { user ->
            if (user != null) {
                try {
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val doc = firestore.collection("publicUsers").document(user.uid).get().await()
                    if (doc.exists()) {
                        val displayName = doc.getString("displayName")
                        val username = doc.getString("username")
                        if (!displayName.isNullOrBlank()) {
                            userRepository.hydrateAlias(user.uid, displayName)
                        }
                        if (!username.isNullOrBlank()) {
                            userRepository.hydrateUsername(user.uid, username)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
