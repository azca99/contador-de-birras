package com.example.contadordebirras

import android.app.Application
import android.util.Log
import com.example.contadordebirras.sync.c4.C4AppSyncManager
import com.example.contadordebirras.sync.c4.C4SyncConstants
import com.example.contadordebirras.sync.c4.C4SyncProviderRegistry
import com.example.contadordebirras.sync.c4.C4TestEnvironmentFactory
import com.example.contadordebirras.sync.c4.StagingC4SyncProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
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

        if (BuildConfig.DEBUG) {
            // REQUISITO 2: Validación estricta de aislamiento del piloto DEBUG.
            // Firebase exclusivamente contra Emulator y proyecto demo-contador-c44.
            val currentProject = FirebaseApp.getInstance().options.projectId
            if (currentProject != C4SyncConstants.DEMO_PROJECT_ID) {
                val errorMsg = "BLOQUEO DE SEGURIDAD PILOTO C4: El proyecto Firebase configurado ('$currentProject') " +
                        "no coincide con el proyecto exclusivo de prueba '${C4SyncConstants.DEMO_PROJECT_ID}'. " +
                        "Se bloquea la aplicación para proteger producción."
                Log.e("C4PilotSecurity", errorMsg)
                throw SecurityException(errorMsg)
            }

            try {
                FirebaseAuth.getInstance().useEmulator(
                    C4SyncConstants.EMULATOR_HOST_ANDROID,
                    C4TestEnvironmentFactory.REQUIRED_AUTH_PORT
                )
                FirebaseFirestore.getInstance().useEmulator(
                    C4SyncConstants.EMULATOR_HOST_ANDROID,
                    C4SyncConstants.FIRESTORE_PORT
                )
                FirebaseStorage.getInstance().useEmulator(
                    C4SyncConstants.EMULATOR_HOST_ANDROID,
                    C4SyncConstants.STORAGE_PORT
                )
                Log.i("C4PilotSecurity", "Firebase Emulator configurado con éxito para piloto C4 (${C4SyncConstants.EMULATOR_HOST_ANDROID})")
            } catch (e: Exception) {
                Log.e("C4PilotSecurity", "Error al configurar emulador Firebase: ${e.message}", e)
                throw SecurityException("BLOQUEO DE SEGURIDAD PILOTO C4: No se pudo redirigir Firebase hacia el emulador local.", e)
            }

            // REQUISITO 3: Registrar el proveedor C4 de staging para permitir sincronización mediante WorkManager
            C4SyncProviderRegistry.setProvider(
                StagingC4SyncProvider { context, ownerUid ->
                    val host = C4SyncConstants.EMULATOR_HOST_ANDROID
                    val instances = C4TestEnvironmentFactory.createIsolatedInstances(
                        context = context,
                        host = host,
                        projectId = C4SyncConstants.DEMO_PROJECT_ID
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
        }

        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        firebaseAppCheck.installAppCheckProviderFactory(
            if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance() else PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }
}
