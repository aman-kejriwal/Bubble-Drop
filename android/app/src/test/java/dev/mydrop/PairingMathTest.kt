package dev.mydrop

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors produced by the Swift implementation (mac/Sources/MyDropCore/Security.swift). */
class PairingMathTest {
    private val client = "aa".repeat(32)
    private val server = "bb".repeat(32)
    private val clientNonce = ByteArray(32) { it.toByte() }
    private val serverNonce = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun commitmentMatchesSwift() {
        val hex = PairingMath.commitment(client, server, serverNonce).joinToString("") { "%02x".format(it) }
        assertEquals("eabcfddbe7d5dd6d8a56dca24170a66259d6296d06b22e847fd8dbc7f950006c", hex)
    }

    @Test
    fun codeMatchesSwift() {
        assertEquals("522975", PairingMath.code(client, server, clientNonce, serverNonce))
    }

    @Test
    fun sanitizeMatchesSwift() {
        assertEquals("evil_name.txt", sanitizeFileName("..evil:name.txt"))
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("file", sanitizeFileName("..."))
    }
}
