package com.example.contadordebirras.sync.c4

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagingC4SyncProviderTest {

    private lateinit var context: Context
    private lateinit var mockManager: C4AppSyncManager
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockUser: FirebaseUser

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockManager = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        mockUser = mockk(relaxed = true)
        every { mockManager.auth } returns mockAuth
    }

    @Test
    fun `syncUser returns InactiveOrBlocked when currentUser is null in Firestore Auth`() = runTest {
        every { mockAuth.currentUser } returns null

        val provider = StagingC4SyncProvider { _, _ -> mockManager }
        val result = provider.syncUser(context, "worker_uid")

        assertTrue(result is C4ProviderSyncResult.InactiveOrBlocked)
        val reason = (result as C4ProviderSyncResult.InactiveOrBlocked).reason
        assertTrue(reason.contains("no coincide"))
        verify(exactly = 1) { mockManager.close() }
    }

    @Test
    fun `syncUser returns InactiveOrBlocked when currentUser uid does not match worker uid`() = runTest {
        every { mockUser.uid } returns "other_user_uid"
        every { mockAuth.currentUser } returns mockUser

        val provider = StagingC4SyncProvider { _, _ -> mockManager }
        val result = provider.syncUser(context, "worker_uid")

        assertTrue(result is C4ProviderSyncResult.InactiveOrBlocked)
        val reason = (result as C4ProviderSyncResult.InactiveOrBlocked).reason
        assertTrue(reason.contains("other_user_uid"))
        assertTrue(reason.contains("worker_uid"))
        verify(exactly = 1) { mockManager.close() }
    }

    @Test
    fun `syncUser returns InactiveOrBlocked when syncManagerProvider throws SecurityException`() = runTest {
        val provider = StagingC4SyncProvider { _, _ ->
            throw SecurityException("Host no autorizado")
        }
        val result = provider.syncUser(context, "worker_uid")

        assertTrue(result is C4ProviderSyncResult.InactiveOrBlocked)
        val reason = (result as C4ProviderSyncResult.InactiveOrBlocked).reason
        assertTrue(reason.contains("Bloqueo de seguridad"))
    }

    @Test
    fun `syncUser returns InactiveOrBlocked when syncManagerProvider throws IllegalStateException`() = runTest {
        val provider = StagingC4SyncProvider { _, _ ->
            throw IllegalStateException("Sesión no válida")
        }
        val result = provider.syncUser(context, "worker_uid")

        assertTrue(result is C4ProviderSyncResult.InactiveOrBlocked)
        val reason = (result as C4ProviderSyncResult.InactiveOrBlocked).reason
        assertTrue(reason.contains("Bloqueo en StagingC4SyncProvider"))
    }

    @Test
    fun `syncUser executes push and pull and returns Success when UIDs match`() = runTest {
        every { mockUser.uid } returns "worker_uid"
        every { mockAuth.currentUser } returns mockUser
        coEvery { mockManager.pushAllPending() } returns mapOf(
            "beer_1" to C4CoordinatorPushResult.Success(mockk(relaxed = true), 1L)
        )
        coEvery { mockManager.pullRemoteCollection() } returns mapOf(
            "beer_1" to C4CoordinatorPullResult.NoOp
        )

        val provider = StagingC4SyncProvider { _, _ -> mockManager }
        val result = provider.syncUser(context, "worker_uid")

        assertEquals(C4ProviderSyncResult.Success, result)
        coVerify(exactly = 1) { mockManager.pushAllPending() }
        coVerify(exactly = 1) { mockManager.pullRemoteCollection() }
        verify(exactly = 1) { mockManager.close() }
    }
}
