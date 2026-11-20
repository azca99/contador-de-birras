package com.example.contadordebirras

import android.app.Application
import android.util.Log
import com.example.contadordebirras.sync.c4.C4AppSyncManager
import com.example.contadordebirras.sync.c4.C4SyncConstants
import com.example.contadordebirras.sync.c4.C4SyncProviderRegistry
import com.example.contadordebirras.sync.c4.C4TestEnvironmentFactory
import com.example.contadordebirras.sync.c4.ProductionC4SyncProvider
import com.example.contadordebirras.sync.c4.StagingC4SyncProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class ContadorDeBirrasApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }

        val isPilot = packageName.endsWith(".c4pilot")
        if (BuildConfig.DEBUG && isPilot) {
            // Entorno exclusivo de pruebas y piloto aislados
            val currentProject = FirebaseApp.getInstance().options.projectId
            if (currentProject == C4SyncConstants.DEMO_PROJECT_ID) {
                try {
                    FirebaseAuth.getInstance().useEmulator(
                        C4SyncConstants.EMULATOR_HOST_ANDROID,
                        C4TestEnvironmentFactory.REQUIRED_AUTH_PORT
                    )
                    val firestore = FirebaseFirestore.getInstance()
                    firestore.useEmulator(
                        C4SyncConstants.EMULATOR_HOST_ANDROID,
                        C4SyncConstants.FIRESTORE_PORT
                    )
                    firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                        .setHost("${C4SyncConstants.EMULATOR_HOST_ANDROID}:${C4SyncConstants.FIRESTORE_PORT}")
                        .setSslEnabled(false)
                        .setPersistenceEnabled(false)
                        .build()

                    FirebaseStorage.getInstance().useEmulator(
                        C4SyncConstants.EMULATOR_HOST_ANDROID,
                        C4SyncConstants.STORAGE_PORT
                    )
                    Log.i("C4PilotSecurity", "Firebase Emulator configurado con éxito para piloto C4 (${C4SyncConstants.EMULATOR_HOST_ANDROID})")
                } catch (e: Exception) {
                    Log.e("C4PilotSecurity", "Error al configurar emulador Firebase: ${e.message}", e)
                }
            }

            C4SyncProviderRegistry.setProvider(
                StagingC4SyncProvider { context, ownerUid ->
                    val host = C4SyncConstants.EMULATOR_HOST_ANDROID
                    val instances = C4TestEnvironmentFactory.getAuthenticatedPilotInstances(
                        context = context,
                        expectedUid = ownerUid
                    )
                    C4AppSyncManager.createForTesting(
                        context = context,
                        ownerUid = ownerUid,
                        host = host,
                        inMemory = false,
                        dbName = "c4_debug_disposable.db",
                        instances = instances
                    )
                }
            )
        } else {
            // Arquitectura normal de la aplicación y Release:
            // Registrar ProductionC4SyncProvider (desactivado por defecto hasta autorización expresa)
            C4SyncProviderRegistry.setProvider(ProductionC4SyncProvider())
        }

        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            try {
                val clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                val method = clazz.getMethod("getInstance")
                val factory = method.invoke(null) as AppCheckProviderFactory
                firebaseAppCheck.installAppCheckProviderFactory(factory)
            } catch (e: Exception) {
                Log.w("AppCheck", "DebugAppCheckProviderFactory no disponible en runtime: ${e.message}")
            }
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }
}
