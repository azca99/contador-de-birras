package com.example.contadordebirras.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeerDirtyFieldsCodecTest {

    @Test
    fun `encode returns null for empty set`() {
        assertNull(BeerDirtyFieldsCodec.encode(emptySet()))
    }

    @Test
    fun `encode produces canonical alphabetical order`() {
        val input = setOf(BeerDirtyField.TYPE, BeerDirtyField.COMMENT, BeerDirtyField.LOCATION)
        val encoded = BeerDirtyFieldsCodec.encode(input)
        assertEquals("COMMENT,LOCATION,TYPE", encoded)
    }

    @Test
    fun `encode ALL_FIELDS produces canonical full string`() {
        val encoded = BeerDirtyFieldsCodec.encode(BeerDirtyFieldsCodec.ALL_FIELDS)
        assertEquals("COMMENT,LOCATION,PHOTO,TIMESTAMP,TYPE", encoded)
    }

    @Test
    fun `decode returns empty set for null or blank input`() {
        assertTrue(BeerDirtyFieldsCodec.decode(null).isEmpty())
        assertTrue(BeerDirtyFieldsCodec.decode("").isEmpty())
        assertTrue(BeerDirtyFieldsCodec.decode("   ").isEmpty())
    }

    @Test
    fun `decode parses single and multiple valid fields`() {
        val single = BeerDirtyFieldsCodec.decode("LOCATION")
        assertEquals(setOf(BeerDirtyField.LOCATION), single)

        val multiple = BeerDirtyFieldsCodec.decode("COMMENT,LOCATION,PHOTO")
        assertEquals(setOf(BeerDirtyField.COMMENT, BeerDirtyField.LOCATION, BeerDirtyField.PHOTO), multiple)
    }

    @Test
    fun `decode ignores unknown or malformed tokens and trims whitespace`() {
        val decoded = BeerDirtyFieldsCodec.decode(" COMMENT , UNKNOWN_FIELD , LOCATION , , ")
        assertEquals(setOf(BeerDirtyField.COMMENT, BeerDirtyField.LOCATION), decoded)
    }

    @Test
    fun `decode deduplicates entries`() {
        val decoded = BeerDirtyFieldsCodec.decode("LOCATION,LOCATION,COMMENT")
        assertEquals(setOf(BeerDirtyField.LOCATION, BeerDirtyField.COMMENT), decoded)
    }

    @Test
    fun `roundtrip preserves every single field`() {
        for (field in BeerDirtyField.values()) {
            val encoded = BeerDirtyFieldsCodec.encode(setOf(field))
            val decoded = BeerDirtyFieldsCodec.decode(encoded)
            assertEquals(setOf(field), decoded)
        }
    }

    @Test
    fun `merge null with new field returns encoded field`() {
        val merged = BeerDirtyFieldsCodec.merge(null, setOf(BeerDirtyField.LOCATION))
        assertEquals("LOCATION", merged)
    }

    @Test
    fun `merge existing COMMENT with LOCATION returns canonical order`() {
        val merged = BeerDirtyFieldsCodec.merge("COMMENT", setOf(BeerDirtyField.LOCATION))
        assertEquals("COMMENT,LOCATION", merged)
    }

    @Test
    fun `merge existing LOCATION with LOCATION does not duplicate`() {
        val merged = BeerDirtyFieldsCodec.merge("LOCATION", setOf(BeerDirtyField.LOCATION))
        assertEquals("LOCATION", merged)
    }

    @Test
    fun `merge preserves existing fields and maintains canonical sorted output`() {
        val merged = BeerDirtyFieldsCodec.merge("TYPE,PHOTO", setOf(BeerDirtyField.COMMENT, BeerDirtyField.LOCATION))
        assertEquals("COMMENT,LOCATION,PHOTO,TYPE", merged)
    }
}
