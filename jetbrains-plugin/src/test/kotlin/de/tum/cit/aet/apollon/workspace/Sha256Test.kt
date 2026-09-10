package de.tum.cit.aet.apollon.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Sha256Test {
    @Test
    fun `same bytes hash the same way`() {
        val bytes = "hello world".toByteArray()
        assertEquals(sha256Hex(bytes), sha256Hex(bytes.copyOf()))
    }

    @Test
    fun `different bytes hash differently`() {
        assertNotEquals(sha256Hex("a".toByteArray()), sha256Hex("b".toByteArray()))
    }

    @Test
    fun `hex digest is 64 lowercase hex characters`() {
        val hex = sha256Hex("anything".toByteArray())
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assertEquals(true, hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun `sourceHash is prefixed`() {
        val bytes = "content".toByteArray()
        assertEquals("sha256:${sha256Hex(bytes)}", sourceHash(bytes))
    }
}
