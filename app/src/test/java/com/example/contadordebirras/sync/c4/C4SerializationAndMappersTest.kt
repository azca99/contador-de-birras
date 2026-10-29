package com.example.contadordebirras.sync.c4

import com.example.contadordebirras.domain.BeerDirtyField
import com.example.contadordebirras.domain.BeerDirtyFieldsCodec
import com.example.contadordebirras.domain.BeerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class C4SerializationAndMappersTest {

    @Test
    fun c4FirestoreDocument_serialization_containsAllMandatoryFieldsAndRevisions() {
        val fieldRevisions = mapOf(
            "TYPE" to 1L,
            "TIMESTAMP" to 1L,
            "LOCATION" to 1L,
            "COMMENT" to 1L,
            "PHOTO" to 1L
        )

        val doc = C4FirestoreDocument(
            syncId = "beer-123",
            userId = "alice",
            type = BeerType.LATA.name,
            timestamp = 1000L,
            latitude = 40.4168,
            longitude = -3.7038,
            comment = "Cold beer",
            locationName = "Madrid",
            remotePhotoUrl = "https://storage/photo.jpg",
            photoSource = "CAMERA",
            revision = 1L,
            isDeleted = false,
            lastMutationId = "mut-001",
            updatedAt = 2000L,
            fieldRevisions = fieldRevisions
        )

        val map = doc.toFirestoreMap()
        assertEquals("beer-123", map["syncId"])
        assertEquals("alice", map["userId"])
        assertEquals("LATA", map["type"])
        assertEquals(1000L, map["timestamp"])
        assertEquals(40.4168, map["latitude"])
        assertEquals(-3.7038, map["longitude"])
        assertEquals("Cold beer", map["comment"])
        assertEquals("Madrid", map["locationName"])
        assertEquals("https://storage/photo.jpg", map["remotePhotoUrl"])
        assertEquals("CAMERA", map["photoSource"])
        assertEquals(1L, map["revision"])
        assertEquals(false, map["isDeleted"])
        assertEquals("mut-001", map["lastMutationId"])
        assertEquals(2000L, map["updatedAt"])

        @Suppress("UNCHECKED_CAST")
        val fr = map["fieldRevisions"] as Map<String, Long>
        assertEquals(5, fr.size)
        assertEquals(1L, fr["TYPE"])
        assertEquals(1L, fr["TIMESTAMP"])
        assertEquals(1L, fr["LOCATION"])
        assertEquals(1L, fr["COMMENT"])
        assertEquals(1L, fr["PHOTO"])
    }

    @Test
    fun c4SyncConstants_hasKnownFiveKeys() {
        assertEquals(5, C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.size)
        assertTrue(C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.contains("TYPE"))
        assertTrue(C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.contains("TIMESTAMP"))
        assertTrue(C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.contains("LOCATION"))
        assertTrue(C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.contains("COMMENT"))
        assertTrue(C4SyncConstants.KNOWN_FIELD_REVISIONS_KEYS.contains("PHOTO"))
    }

    @Test
    fun beerDirtyFieldsCodec_roundtripEncoding() {
        val fields = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT)
        val encoded = BeerDirtyFieldsCodec.encode(fields)
        assertNotNull(encoded)

        val decoded = BeerDirtyFieldsCodec.decode(encoded)
        assertEquals(fields, decoded)
    }

    @Test
    fun beerDirtyFieldsCodec_emptySet_encodesToNull() {
        val empty = emptySet<BeerDirtyField>()
        assertNull(BeerDirtyFieldsCodec.encode(empty))
        assertTrue(BeerDirtyFieldsCodec.decode(null).isEmpty())
        assertTrue(BeerDirtyFieldsCodec.decode("").isEmpty())
    }

    @Test
    fun c4SyncConstants_configurationPorts() {
        assertEquals(8888, C4SyncConstants.FIRESTORE_PORT)
        assertEquals(9199, C4SyncConstants.STORAGE_PORT)
        assertEquals("demo-contador-c44", C4SyncConstants.DEMO_PROJECT_ID)
        assertEquals("beers", C4SyncConstants.BEERS_COLLECTION)
    }
}
