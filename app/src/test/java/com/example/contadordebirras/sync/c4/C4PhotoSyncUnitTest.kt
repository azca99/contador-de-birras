package com.example.contadordebirras.sync.c4

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.contadordebirras.data.BeerDao
import com.example.contadordebirras.data.BeerDatabase
import com.example.contadordebirras.data.MutationStatus
import com.example.contadordebirras.data.SyncStatus
import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C4PhotoSyncUnitTest {

    private lateinit var context: Application
    private lateinit var database: BeerDatabase
    private lateinit var beerDao: BeerDao
    private lateinit var persistence: RoomC4SyncPersistence

    private val firestoreAdapter = mockk<FirestoreC4SyncAdapter>()
    private val storageAdapter = mockk<StorageC4SyncAdapter>()
    private lateinit var coordinator: AndroidC4SyncCoordinator
    private lateinit var manager: C4AppSyncManager

    private val ownerUid = "pilot_user_test"
    private val allFieldsEncoded = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS)!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        beerDao = database.beerDao()
        persistence = RoomC4SyncPersistence(database)
        coordinator = AndroidC4SyncCoordinator(firestoreAdapter, storageAdapter, persistence)
        manager = C4AppSyncManager(
            database = database,
            persistence = persistence,
            coordinator = coordinator,
            ownerUid = ownerUid,
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pushAllPending_resolvesPhotoFromInternalFile_andUploadsSuccessfully() = runBlocking {
        // 1. Crear archivo real privado en filesDir (simulando ImageUtils.compressAndSaveImage)
        val imagesDir = File(context.filesDir, "images").apply { if (!exists()) mkdirs() }
        val testFile = File(imagesDir, "beer_${UUID.randomUUID()}.jpg")
        val sampleBytes = byteArrayOf(10, 20, 30, 40, 50)
        testFile.writeBytes(sampleBytes)
        val fileUri = Uri.fromFile(testFile).toString()

        // 2. Registrar cerveza con foto en Room
        val syncId = manager.createBeer(
            type = BeerType.JARRA,
            comment = "Birra piloto con foto",
            photoUri = fileUri
        )

        val pendingBeer = beerDao.getBeerBySyncId(syncId, ownerUid)
        assertNotNull(pendingBeer)
        assertEquals(SyncStatus.PENDING, pendingBeer!!.syncStatus)
        assertEquals(fileUri, pendingBeer.photoUri)

        val mutationId = pendingBeer.lastMutationId!!
        val expectedRemotePath = C4SyncConstants.buildVersionedPhotoPath(ownerUid, syncId, mutationId)

        // 3. Simular éxito de subida y existencia en Storage emulator
        coEvery {
            storageAdapter.uploadVersionedPhoto(
                ownerUid = ownerUid,
                syncId = syncId,
                mutationId = mutationId,
                photoBytes = sampleBytes
            )
        } returns C4StorageResult.Success(expectedRemotePath, alreadyExisted = false)

        coEvery { storageAdapter.photoExists(expectedRemotePath) } returns true
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null
        coEvery { firestoreAdapter.executeCreateActive(syncId, ownerUid, any(), mutationId) } returns
                C4TransactionResult.Success(1L, mutationId)

        // 4. Ejecutar pushAllPending (como lo hace StagingC4SyncProvider / WorkManager)
        val results = manager.pushAllPending()

        // 5. Verificaciones
        val pushResult = results[syncId]
        assertTrue("Push con foto debe ser Success: $pushResult", pushResult is C4CoordinatorPushResult.Success)

        val syncedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.SYNCED, syncedBeer.syncStatus)
        assertEquals(1L, syncedBeer.remoteRevision)
        assertEquals(fileUri, syncedBeer.photoUri) // El archivo local se preserva
        assertEquals(expectedRemotePath, syncedBeer.remotePhotoUrl) // Referencia remota confirmada
        assertNull(syncedBeer.lastMutationId)
        assertNull(syncedBeer.dirtyFields)

        val history = beerDao.getHistoryRecordById(mutationId, ownerUid)!!
        assertEquals(MutationStatus.CONFIRMED, history.status)
        assertEquals(1L, history.confirmedRevision)
    }

    @Test
    fun pushAllPending_whenPhotoFileNotFound_failsGracefullyAndPreservesPendingState() = runBlocking {
        // Cerveza con URI hacia un archivo inexistente
        val missingUri = "file:///data/user/0/com.example/files/images/non_existent.jpg"
        val syncId = manager.createBeer(
            type = BeerType.CANA,
            comment = "Foto perdida",
            photoUri = missingUri
        )

        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val results = manager.pushAllPending()
        val pushResult = results[syncId]

        assertTrue("Debe fallar con StorageFailed: $pushResult", pushResult is C4CoordinatorPushResult.StorageFailed)

        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, beer.syncStatus)
        assertEquals(missingUri, beer.photoUri)
        assertNull(beer.remotePhotoUrl)

        val history = persistence.historyDao.getPendingMutations(ownerUid)
        assertEquals(1, history.size)
        assertEquals(MutationStatus.PENDING, history.first().status)
    }

    @Test
    fun pushAllPending_whenStorageUploadFails_preservesPendingStateWithoutDataLoss() = runBlocking {
        val imagesDir = File(context.filesDir, "images").apply { if (!exists()) mkdirs() }
        val testFile = File(imagesDir, "beer_fail_${UUID.randomUUID()}.jpg")
        val sampleBytes = byteArrayOf(1, 2, 3)
        testFile.writeBytes(sampleBytes)
        val fileUri = Uri.fromFile(testFile).toString()

        val syncId = manager.createBeer(type = BeerType.PINTA, photoUri = fileUri)
        val mutationId = beerDao.getBeerBySyncId(syncId, ownerUid)!!.lastMutationId!!

        coEvery {
            storageAdapter.uploadVersionedPhoto(ownerUid, syncId, mutationId, sampleBytes)
        } returns C4StorageResult.Error(IOException("Storage emulator timeout"))
        coEvery { firestoreAdapter.readServerDocument(syncId) } returns null

        val results = manager.pushAllPending()
        val pushResult = results[syncId]

        assertTrue("Fallo en Storage debe resultar en StorageFailed: $pushResult",
            pushResult is C4CoordinatorPushResult.StorageFailed)

        val beer = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(SyncStatus.PENDING, beer.syncStatus)
        assertEquals(fileUri, beer.photoUri)
        assertNull(beer.remotePhotoUrl)
    }

    @Test
    fun pullRemoteCollection_withRemotePhoto_insertsSyncedBeerWithRemotePhotoUrl() = runBlocking {
        // Base local vacía
        val syncId = UUID.randomUUID().toString()
        val remotePath = "users/$ownerUid/beers/${syncId}_mut1.jpg"

        val remoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = ownerUid,
            type = "JARRA",
            timestamp = 1700000000000L,
            latitude = 40.4,
            longitude = -3.7,
            comment = "Cerveza descargada de otro dispositivo",
            locationName = "Madrid",
            remotePhotoUrl = remotePath,
            photoSource = "CAMERA",
            updatedAt = 1700000000000L,
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut1",
            fieldRevisions = C4FirestoreDocument.canonicalCreationFieldRevisions(1L)
        )

        coEvery { firestoreAdapter.queryServerDocuments(ownerUid) } returns listOf(remoteDoc)

        val results = manager.pullRemoteCollection()
        val pullResult = results[syncId]
        assertTrue("Pull debe aplicarse: $pullResult", pullResult is C4CoordinatorPullResult.Applied)

        val insertedBeer = beerDao.getBeerBySyncId(syncId, ownerUid)
        assertNotNull(insertedBeer)
        assertEquals(SyncStatus.SYNCED, insertedBeer!!.syncStatus)
        assertNull(insertedBeer.photoUri) // No hay archivo local aún
        assertEquals(remotePath, insertedBeer.remotePhotoUrl) // URL/path remoto disponible para SecureFirebaseImage
        assertEquals("CAMERA", insertedBeer.photoSource)
        assertTrue("hasPhoto debe ser true por tener remotePhotoUrl", insertedBeer.hasPhoto())
    }

    @Test
    fun pullRemoteCollection_whenLocalHasPhotoUri_preservesLocalPhotoUriWhileUpdatingRemotePhotoUrl() = runBlocking {
        val syncId = UUID.randomUUID().toString()
        val localFileUri = "file:///data/user/0/images/my_local_photo.jpg"

        // Insertar cerveza local previa ya sincronizada en rev 1 con photoUri local
        val initialBeer = com.example.contadordebirras.data.BeerEntity(
            id = 0,
            type = BeerType.LATA,
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            photoUri = localFileUri,
            comment = "Original",
            locationName = null,
            syncId = syncId,
            syncStatus = SyncStatus.SYNCED,
            remotePhotoUrl = "users/$ownerUid/beers/${syncId}_mut1.jpg",
            updatedAt = 1000L,
            photoSource = "GALLERY",
            ownerUid = ownerUid,
            remoteRevision = 1L,
            baseRevision = 1L,
            dirtyFields = null,
            lastMutationId = null,
            deletedAt = null
        )
        beerDao.insertBeer(initialBeer)

        val updatedRemotePath = "users/$ownerUid/beers/${syncId}_mut2.jpg"
        val updatedRemoteDoc = C4FirestoreDocument(
            syncId = syncId,
            userId = ownerUid,
            type = "LATA",
            timestamp = 1000L,
            latitude = null,
            longitude = null,
            comment = "Actualizado remotamente",
            locationName = null,
            remotePhotoUrl = updatedRemotePath,
            photoSource = "GALLERY",
            updatedAt = 2000L,
            revision = 2L,
            isDeleted = false,
            lastMutationId = "mut2",
            fieldRevisions = mapOf(
                "TYPE" to 1L,
                "TIMESTAMP" to 1L,
                "LOCATION" to 1L,
                "COMMENT" to 2L,
                "PHOTO" to 2L
            )
        )

        coEvery { firestoreAdapter.queryServerDocuments(ownerUid) } returns listOf(updatedRemoteDoc)

        val results = manager.pullRemoteCollection()
        assertTrue(results[syncId] is C4CoordinatorPullResult.Applied)

        val updatedLocal = beerDao.getBeerBySyncId(syncId, ownerUid)!!
        assertEquals(2L, updatedLocal.remoteRevision)
        assertEquals(localFileUri, updatedLocal.photoUri) // photoUri local preservado intacto
        assertEquals(updatedRemotePath, updatedLocal.remotePhotoUrl) // remotePhotoUrl actualizado
    }

    @Test
    fun storageC4SyncAdapter_idempotentRetry_matchesContentWhenMd5IsNull() = runBlocking {
        val mockStorage = mockk<FirebaseStorage>()
        val mockApp = mockk<FirebaseApp>()
        val mockOptions = mockk<FirebaseOptions>()
        val mockRootRef = mockk<StorageReference>()
        val mockFileRef = mockk<StorageReference>()
        val mockMeta = mockk<StorageMetadata>()

        every { mockStorage.app } returns mockApp
        every { mockApp.options } returns mockOptions
        every { mockOptions.projectId } returns C4SyncConstants.DEMO_PROJECT_ID
        every { mockStorage.reference } returns mockRootRef

        val syncId = UUID.randomUUID().toString()
        val mutationId = UUID.randomUUID().toString()
        val remotePath = C4SyncConstants.buildVersionedPhotoPath(ownerUid, syncId, mutationId)
        val photoBytes = byteArrayOf(1, 2, 3, 4, 5)

        every { mockRootRef.child(remotePath) } returns mockFileRef
        // Metadatos remotos existen pero sin md5Hash
        every { mockMeta.md5Hash } returns null
        every { mockMeta.sizeBytes } returns 5L
        every { mockFileRef.metadata } returns Tasks.forResult(mockMeta)

        // Descarga de comprobación devuelve contenido exactamente idéntico
        every { mockFileRef.getBytes(6L) } returns Tasks.forResult(photoBytes)

        val adapter = StorageC4SyncAdapter(mockStorage)
        val result = adapter.uploadVersionedPhoto(ownerUid, syncId, mutationId, photoBytes)

        assertTrue("Debe tener éxito con alreadyExisted=true", result is C4StorageResult.Success)
        val success = result as C4StorageResult.Success
        assertEquals(remotePath, success.remotePath)
        assertTrue(success.alreadyExisted)
    }

    @Test
    fun storageC4SyncAdapter_downloadPhoto_retrievesBytesOrNullOnFailure() = runBlocking {
        val mockStorage = mockk<FirebaseStorage>()
        val mockApp = mockk<FirebaseApp>()
        val mockOptions = mockk<FirebaseOptions>()
        val mockRootRef = mockk<StorageReference>()
        val mockFileRef = mockk<StorageReference>()

        every { mockStorage.app } returns mockApp
        every { mockApp.options } returns mockOptions
        every { mockOptions.projectId } returns C4SyncConstants.DEMO_PROJECT_ID
        every { mockStorage.reference } returns mockRootRef

        val remotePath = "users/$ownerUid/beers/test_download.jpg"
        val expectedBytes = byteArrayOf(99, 100, 101)

        every { mockRootRef.child(remotePath) } returns mockFileRef
        every { mockFileRef.getBytes(any()) } returns Tasks.forResult(expectedBytes)

        val adapter = StorageC4SyncAdapter(mockStorage)
        val downloaded = adapter.downloadPhoto(remotePath)
        assertNotNull(downloaded)
        assertTrue(downloaded!!.contentEquals(expectedBytes))

        // Simular fallo
        every { mockFileRef.getBytes(any()) } returns Tasks.forException(IOException("Network error"))
        val failedDownload = adapter.downloadPhoto(remotePath)
        assertNull(failedDownload)
    }
}
