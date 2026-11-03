package com.example.contadordebirras.sync.c4

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StorageC4SyncAdapterTest {

    @Test
    fun versionedPhotoNaming_acceptsValidUuidAndPrefixedIdentifiers() {
        val syncUuid = UUID.randomUUID().toString()
        val mutUuid = UUID.randomUUID().toString()

        assertTrue(C4SyncConstants.isVersionedPhotoName("${syncUuid}_$mutUuid.jpg"))
        assertTrue(C4SyncConstants.isVersionedPhotoName("${syncUuid}_$mutUuid.jpeg"))
        assertTrue(C4SyncConstants.isVersionedPhotoName("${syncUuid}_$mutUuid.png"))
        assertTrue(C4SyncConstants.isVersionedPhotoName("${syncUuid}_$mutUuid.webp"))

        assertTrue(C4SyncConstants.isVersionedPhotoName("sync-beer-1001_mut-tx-2001.jpg"))
        assertTrue(C4SyncConstants.isVersionedPhotoName("sync-abc_mut-xyz.png"))
    }

    @Test
    fun versionedPhotoNaming_rejectsUnversionedOrArbitraryNames() {
        assertFalse(C4SyncConstants.isVersionedPhotoName("photo.jpg"))
        assertFalse(C4SyncConstants.isVersionedPhotoName("mi_cerveza.jpg"))
        assertFalse(C4SyncConstants.isVersionedPhotoName("beer_photo_123.jpg"))
        assertFalse(C4SyncConstants.isVersionedPhotoName("beer123.gif"))
        assertFalse(C4SyncConstants.isVersionedPhotoName("sync-123.jpg"))
        assertFalse(C4SyncConstants.isVersionedPhotoName("_mut-123.jpg"))
    }

    @Test
    fun buildVersionedPhotoPath_formatsExactStoragePath() {
        val ownerUid = "user_alice_test"
        val syncId = "sync-beer-101"
        val mutationId = "mut-init-202"

        val path = C4SyncConstants.buildVersionedPhotoPath(ownerUid, syncId, mutationId)
        assertEquals("users/user_alice_test/beers/sync-beer-101_mut-init-202.jpg", path)
    }

    @Test
    fun computeMd5Base64_producesDeterministicHash() {
        val bytes1 = "hello world".toByteArray(Charsets.UTF_8)
        val hash1 = StorageC4SyncAdapter.computeMd5Base64(bytes1)
        val hash2 = StorageC4SyncAdapter.computeMd5Base64(bytes1)
        assertEquals(hash1, hash2)

        val bytes2 = "different content".toByteArray(Charsets.UTF_8)
        val hash3 = StorageC4SyncAdapter.computeMd5Base64(bytes2)
        assertFalse("Different bytes must produce different MD5", hash1 == hash3)
    }
}
