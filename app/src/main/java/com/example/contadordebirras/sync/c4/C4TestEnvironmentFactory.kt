package com.example.contadordebirras.sync.c4

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.storage.FirebaseStorage

/**
 * Fábrica aislada para inicializar entornos de prueba del Protocolo C4 con emuladores locales.
 *
 * GARANTÍAS DE SEGURIDAD Y AISLAMIENTO:
 * - Configura explícitamente Auth, Firestore y Storage con el proyecto ficticio [C4SyncConstants.DEMO_PROJECT_ID].
 * - Enlaza obligatoriamente los emuladores locales (127.0.0.1 o 10.0.2.2) en sus puertos asignados.
 * - Deshabilita SSL y persistencia de disco para asegurar ejecuciones herméticas e independientes.
 * - Prohíbe cualquier fallback a Firebase de producción verificando el host de conexión.
 */
object C4TestEnvironmentFactory {

    data class IsolatedFirebaseInstances(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val storage: FirebaseStorage
    )

    fun createIsolatedInstances(
        context: Context,
        host: String = C4SyncConstants.EMULATOR_HOST_ANDROID,
        authPort: Int = 9099,
        firestorePort: Int = C4SyncConstants.FIRESTORE_PORT,
        storagePort: Int = C4SyncConstants.STORAGE_PORT,
        projectId: String = C4SyncConstants.DEMO_PROJECT_ID,
        instanceSuffix: String = System.nanoTime().toString()
    ): IsolatedFirebaseInstances {
        val appName = "c4-isolated-app-$instanceSuffix"

        val options = FirebaseOptions.Builder()
            .setApplicationId("1:123456789:android:isolated")
            .setProjectId(projectId)
            .setApiKey("fakeApiKeyIsolated")
            .setStorageBucket("$projectId.appspot.com")
            .build()

        val app = FirebaseApp.initializeApp(context, options, appName)

        val auth = FirebaseAuth.getInstance(app).apply {
            useEmulator(host, authPort)
        }

        val firestore = FirebaseFirestore.getInstance(app).apply {
            useEmulator(host, firestorePort)
            val settings = FirebaseFirestoreSettings.Builder()
                .setHost("$host:$firestorePort")
                .setSslEnabled(false)
                .setPersistenceEnabled(false)
                .build()
            firestoreSettings = settings
        }

        val storage = FirebaseStorage.getInstance(app).apply {
            useEmulator(host, storagePort)
        }

        // Verificación estricta anti-fallback a producción
        val actualHost = firestore.firestoreSettings.host
        if (!actualHost.contains("127.0.0.1") && !actualHost.contains("10.0.2.2") && !actualHost.contains("localhost")) {
            app.delete()
            throw IllegalStateException("PROHIBIDO: Fallback a producción detectado en fábrica aislada. Host: $actualHost")
        }

        return IsolatedFirebaseInstances(app, auth, firestore, storage)
    }
}
