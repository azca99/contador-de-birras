package com.example.contadordebirras

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.UserRepository
import com.example.contadordebirras.domain.AuthRepository
import com.example.contadordebirras.domain.BeerRepository
import com.example.contadordebirras.domain.FriendsRepository
import com.example.contadordebirras.navigation.AppNavigation
import com.example.contadordebirras.ui.AppViewModelFactory
import com.example.contadordebirras.theme.ContadorDeBirrasTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    val beerDatabase = BeerDatabase.getDatabase(this)
    val beerRepository = BeerRepository(beerDatabase.beerDao(), this)
    val userRepository = UserRepository(this)
    val authRepository = AuthRepository(this)
    val friendsRepository = FriendsRepository()
    val groupsRepository = com.example.contadordebirras.domain.GroupsRepository()
    val achievementRepository = com.example.contadordebirras.data.achievements.DefaultAchievementRepository(beerDatabase.achievementDao())
    val factory = AppViewModelFactory(beerRepository, userRepository, authRepository, friendsRepository, groupsRepository, achievementRepository)

    lifecycleScope.launch {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
            val currentAlias = userRepository.userAlias.first()
            authRepository.syncProfile(currentAlias)
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
