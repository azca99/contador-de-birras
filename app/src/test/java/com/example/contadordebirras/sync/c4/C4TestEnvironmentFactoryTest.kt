package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C4TestEnvironmentFactoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun rejects_invalidProjectId_beforeInitializingFirebaseApp() {
        val ex = assertThrows(SecurityException::class.java) {
            C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                projectId = "contador-de-birras-prod" // Proyecto no demo
            )
        }
        assertTrue(ex.message!!.contains("projectId no autorizado"))
    }

    @Test
    fun rejects_unauthorizedHost_beforeInitializingFirebaseApp() {
        val ex = assertThrows(SecurityException::class.java) {
            C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                host = "firestore.googleapis.com" // Host remoto prohibido
            )
        }
        assertTrue(ex.message!!.contains("host no autorizado"))
    }

    @Test
    fun rejects_invalidAuthPort_beforeInitializingFirebaseApp() {
        val ex = assertThrows(SecurityException::class.java) {
            C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                authPort = 443 // Puerto estándar SSL en lugar de 9099
            )
        }
        assertTrue(ex.message!!.contains("puerto Auth"))
    }

    @Test
    fun rejects_invalidFirestorePort_beforeInitializingFirebaseApp() {
        val ex = assertThrows(SecurityException::class.java) {
            C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                firestorePort = 8080 // Puerto divergente a 8888
            )
        }
        assertTrue(ex.message!!.contains("puerto Firestore"))
    }

    @Test
    fun rejects_invalidStoragePort_beforeInitializingFirebaseApp() {
        val ex = assertThrows(SecurityException::class.java) {
            C4TestEnvironmentFactory.createIsolatedInstances(
                context = context,
                storagePort = 9000 // Puerto divergente a 9199
            )
        }
        assertTrue(ex.message!!.contains("puerto Storage"))
    }
}
