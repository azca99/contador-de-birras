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
 * - Valida obligatoriamente ANTES de instanciar FirebaseApp el projectId (demo-contador-c44),
 *   los puertos oficiales (9099, 8888, 9199) y hosts locales autorizados (127.0.0.1, 10.0.2.2, localhost).
 * - Rechaza cualquier configuración divergente mediante SecurityException antes de crear instancias.
 * - Deshabilita SSL y persistencia de disco para asegurar ejecuciones herméticas e independientes.
 * - Prohíbe cualquier fallback a Firebase de producción verificando el host de conexión.
 */
object C4TestEnvironmentFactory {

    const val REQUIRED_PROJECT_ID = "demo-contador-c44"
    const val REQUIRED_AUTH_PORT = 9099
    const val REQUIRED_FIRESTORE_PORT = 8888
    const val REQUIRED_STORAGE_PORT = 9199
    val AUTHORIZED_HOSTS = setOf("127.0.0.1", "10.0.2.2", "localhost")

    data class IsolatedFirebaseInstances(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val storage: FirebaseStorage
    )

    fun createIsolatedInstances(
        context: Context,
        host: String = C4SyncConstants.EMULATOR_HOST_ANDROID,
        authPort: Int = REQUIRED_AUTH_PORT,
        firestorePort: Int = REQUIRED_FIRESTORE_PORT,
        storagePort: Int = REQUIRED_STORAGE_PORT,
        projectId: String = REQUIRED_PROJECT_ID,
        instanceSuffix: String = System.nanoTime().toString()
    ): IsolatedFirebaseInstances {
        // 1. VALIDACIÓN PREVIA ESTRICTA ANTES DE CREAR FIREBASEAPP
        if (projectId != REQUIRED_PROJECT_ID) {
            throw SecurityException("PROHIBIDO: projectId no autorizado '$projectId'. Exclusivamente se permite '$REQUIRED_PROJECT_ID'.")
        }
        if (!AUTHORIZED_HOSTS.contains(host)) {
            throw SecurityException("PROHIBIDO: host no autorizado '$host'. Solo se autorizan emuladores locales en $AUTHORIZED_HOSTS.")
        }
        if (authPort != REQUIRED_AUTH_PORT) {
            throw SecurityException("PROHIBIDO: puerto Auth $authPort inválido. Exclusivamente se permite $REQUIRED_AUTH_PORT.")
        }
        if (firestorePort != REQUIRED_FIRESTORE_PORT) {
            throw SecurityException("PROHIBIDO: puerto Firestore $firestorePort inválido. Exclusivamente se permite $REQUIRED_FIRESTORE_PORT.")
        }
        if (storagePort != REQUIRED_STORAGE_PORT) {
            throw SecurityException("PROHIBIDO: puerto Storage $storagePort inválido. Exclusivamente se permite $REQUIRED_STORAGE_PORT.")
        }

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

        // 2. Verificación estricta anti-fallback a producción post-configuración
        val actualHost = firestore.firestoreSettings.host
        if (!actualHost.contains("127.0.0.1") && !actualHost.contains("10.0.2.2") && !actualHost.contains("localhost")) {
            app.delete()
            throw IllegalStateException("PROHIBIDO: Fallback a producción detectado en fábrica aislada. Host: $actualHost")
        }

        return IsolatedFirebaseInstances(app, auth, firestore, storage)
    }

    /**
     * Obtiene y valida las instancias predeterminadas de Firebase reutilizando de forma segura
     * la sesión autenticada del usuario piloto en el proyecto demo-contador-c44.
     *
     * Valida de forma estricta:
     * - projectId == "demo-contador-c44"
     * - host local de emulador (127.0.0.1, 10.0.2.2 o localhost)
     * - usuario autenticado presente
     * - concordancia obligatoria con expectedUid si se proporciona
     */
    fun getAuthenticatedPilotInstances(
        context: Context,
        expectedUid: String? = null
    ): IsolatedFirebaseInstances {
        val app = try {
            FirebaseApp.getInstance()
        } catch (e: IllegalStateException) {
            throw IllegalStateException("FirebaseApp no inicializada. Debe ejecutarse dentro de la aplicación piloto con emulador.", e)
        }

        val projectId = app.options.projectId
        if (projectId != REQUIRED_PROJECT_ID) {
            throw SecurityException("PROHIBIDO: projectId no autorizado '$projectId'. Exclusivamente se permite '$REQUIRED_PROJECT_ID'.")
        }

        val auth = FirebaseAuth.getInstance(app)
        val currentUid = auth.currentUser?.uid
        if (currentUid.isNullOrBlank()) {
            throw IllegalStateException("No hay una sesión de usuario piloto autenticado en Firebase Auth Emulator.")
        }
        if (expectedUid != null && currentUid != expectedUid) {
            throw IllegalStateException("El UID de Firestore/Auth ($currentUid) no coincide con el UID esperado ($expectedUid).")
        }

        val firestore = FirebaseFirestore.getInstance(app)
        val host = firestore.firestoreSettings.host
        if (!host.contains("127.0.0.1") && !host.contains("10.0.2.2") && !host.contains("localhost")) {
            throw SecurityException("PROHIBIDO: Fallback a producción detectado en Firestore. Host actual: $host")
        }

        val storage = FirebaseStorage.getInstance(app)
        return IsolatedFirebaseInstances(app, auth, firestore, storage)
    }
}
