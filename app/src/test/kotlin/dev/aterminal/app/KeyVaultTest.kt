package dev.aterminal.app

import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultTest {
    private val dir = Files.createTempDirectory("vault").toFile()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
    private fun vault(k: () -> SecretKey = { key }) = KeyVault(dir, k)
    private fun contains(hay: ByteArray, needle: String) = String(hay, Charsets.ISO_8859_1).contains(needle)

    @Test fun `cipher round trips and output has no plaintext`() {
        val enc = KeyCipher.encrypt(key, "a", pem)
        assertTrue(KeyCipher.isEncrypted(enc))
        assertFalse(contains(enc, "OPENSSH"))
        assertArrayEquals(pem, KeyCipher.decrypt(key, "a", enc))
    }

    @Test fun `same input encrypts differently each time`() {
        assertFalse(KeyCipher.encrypt(key, "a", pem).contentEquals(KeyCipher.encrypt(key, "a", pem)))
    }

    @Test fun `tampered file is rejected`() {
        val enc = KeyCipher.encrypt(key, "a", pem)
        enc[enc.size - 1] = (enc[enc.size - 1] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) { KeyCipher.decrypt(key, "a", enc) }
    }

    @Test fun `file copied to another alias is rejected`() {
        val enc = KeyCipher.encrypt(key, "a", pem)
        assertThrows(AEADBadTagException::class.java) { KeyCipher.decrypt(key, "b", enc) }
    }

    @Test fun `plaintext key is not treated as encrypted`() {
        assertFalse(KeyCipher.isEncrypted(pem))
        assertFalse(KeyCipher.isEncrypted(ByteArray(0)))
    }

    @Test fun `store writes ciphertext and load returns the key`() {
        val v = vault()
        v.store("a", pem)
        assertFalse(contains(File(dir, "a").readBytes(), "OPENSSH"))
        assertArrayEquals(pem, v.load("a"))
        assertEquals(listOf("a"), dir.list()!!.toList())
    }

    @Test fun `migrate encrypts legacy files and is idempotent`() {
        File(dir, "old").writeBytes(pem)
        val v = vault()
        assertEquals(KeyVault.Migration(migrated = 1, failed = 0), v.migrate())
        assertFalse(contains(File(dir, "old").readBytes(), "OPENSSH"))
        assertArrayEquals(pem, v.load("old"))
        assertEquals(KeyVault.Migration(migrated = 0, failed = 0), v.migrate())
    }

    @Test fun `migrate failure keeps the original key usable`() {
        File(dir, "old").writeBytes(pem)
        val broken = vault { throw java.security.KeyStoreException("no keystore") }
        assertEquals(KeyVault.Migration(migrated = 0, failed = 1), broken.migrate())
        assertArrayEquals(pem, File(dir, "old").readBytes())
        assertArrayEquals(pem, broken.load("old"))
        assertEquals(listOf("old"), dir.list()!!.toList())
    }

    @Test fun `migrate removes temp files left by a crash and retries`() {
        File(dir, "old").writeBytes(pem)
        File(dir, "old.tmp").writeBytes(byteArrayOf(1, 2))
        assertEquals(KeyVault.Migration(migrated = 1, failed = 0), vault().migrate())
        assertEquals(listOf("old"), dir.list()!!.toList())
    }

    @Test fun `key from another keystore cannot decrypt and the file is kept`() {
        vault().store("a", pem)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val v = vault { other }
        assertThrows(AEADBadTagException::class.java) { v.load("a") }
        assertTrue(File(dir, "a").isFile)
    }

    @Test fun `missing key and path traversal are handled`() {
        val v = vault()
        assertFalse(v.has("nope"))
        assertFalse(v.has("../evil"))
        assertThrows(IllegalArgumentException::class.java) { v.store("../evil", pem) }
    }

    @Test fun `delete removes the file`() {
        val v = vault()
        v.store("a", pem)
        v.delete("a")
        assertFalse(v.has("a"))
    }
}
