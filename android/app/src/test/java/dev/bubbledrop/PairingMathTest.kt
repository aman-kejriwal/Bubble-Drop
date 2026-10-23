package dev.bubbledrop

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors produced by the Swift implementation (mac/Sources/BubbleDropCore/Security.swift). */
class PairingMathTest {
    private val client = "aa".repeat(32)
    private val server = "bb".repeat(32)
    private val clientNonce = ByteArray(32) { it.toByte() }
    private val serverNonce = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun commitmentMatchesSwift() {
        val hex = PairingMath.commitment(client, server, serverNonce).joinToString("") { "%02x".format(it) }
        assertEquals("88ea67776c6f3a81583ab7f797935f1851e8553fb0ae59361d1ff2be64b32fa9", hex)
    }

    @Test
    fun codeMatchesSwift() {
        assertEquals("068199", PairingMath.code(client, server, clientNonce, serverNonce))
    }

    @Test
    fun sanitizeMatchesSwift() {
        assertEquals("evil_name.txt", sanitizeFileName("..evil:name.txt"))
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("file", sanitizeFileName("..."))
    }
}
