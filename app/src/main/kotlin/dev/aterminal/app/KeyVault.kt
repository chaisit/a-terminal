package dev.aterminal.app

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * รูปแบบไฟล์: "ATK1" | ความยาว IV 1 byte | IV | ciphertext+tag ของ AES-256-GCM
 * AAD = alias: คัดลอกไฟล์ไปเป็น alias อื่นจะถอดไม่ออก
 */
internal object KeyCipher {
    private val MAGIC = "ATK1".toByteArray()
    private const val TAG_BITS = 128

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > MAGIC.size && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun encrypt(key: SecretKey, alias: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key) // ให้ provider สร้าง IV เอง: Android Keystore ห้ามกำหนดเอง
        c.updateAAD(alias.toByteArray())
        val body = c.doFinal(plain)
        val iv = c.iv
        return MAGIC + byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun decrypt(key: SecretKey, alias: String, data: ByteArray): ByteArray {
        require(isEncrypted(data)) { "not an encrypted key file" }
        val ivLen = data[MAGIC.size].toInt() and 0xFF
        val ivStart = MAGIC.size + 1
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, ivStart, ivLen))
        c.updateAAD(alias.toByteArray())
        return c.doFinal(data, ivStart + ivLen, data.size - ivStart - ivLen)
    }
}

/**
 * ที่เก็บ private key: ไฟล์บนดิสก์เป็น ciphertext เสมอหลัง migrate
 * ไม่ลบไฟล์เองเมื่อถอดไม่ออก: Keystore อาจมีปัญหาชั่วคราว และ user เป็นคนตัดสินใจ import ใหม่
 */
internal class KeyVault(private val dir: File, private val secretKey: () -> SecretKey) {
    data class Migration(val migrated: Int, val failed: Int)

    fun has(alias: String): Boolean = isSafe(alias) && File(dir, alias).isFile

    fun store(alias: String, plain: ByteArray) {
        require(isSafe(alias)) { "bad alias" }
        dir.mkdirs()
        AtomicText.write(File(dir, alias), KeyCipher.encrypt(secretKey(), alias, plain))
    }

    /** ผู้เรียกต้องล้าง array หลังใช้ ไฟล์ที่ยังไม่ migrate คืนตามเดิมเพื่อไม่ให้ key ใช้ไม่ได้เพราะ Keystore เสีย */
    fun load(alias: String): ByteArray {
        require(isSafe(alias)) { "bad alias" }
        val raw = File(dir, alias).readBytes()
        return if (KeyCipher.isEncrypted(raw)) KeyCipher.decrypt(secretKey(), alias, raw) else raw
    }

    fun delete(alias: String) {
        if (isSafe(alias)) File(dir, alias).delete()
    }

    /**
     * ทำซ้ำได้: ทับไฟล์เดิมด้วย atomic move หลังยืนยันว่าถอดกลับได้ตรงเท่านั้น ล้มเหลวกลางทางไฟล์เดิมยังอยู่
     * ไม่เขียนทับเนื้อเดิมก่อน rename: ไฟดับตรงนั้น key หาย และ flash ก็ไม่รับประกันว่าลบจริงอยู่ดี
     */
    fun migrate(): Migration {
        val files = dir.listFiles()?.filter { it.isFile } ?: return Migration(0, 0)
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        var migrated = 0
        var failed = 0
        for (f in files.filterNot { it.name.endsWith(".tmp") }) {
            try {
                val plain = f.readBytes()
                if (KeyCipher.isEncrypted(plain)) continue
                val key = secretKey()
                val enc = KeyCipher.encrypt(key, f.name, plain)
                check(KeyCipher.decrypt(key, f.name, enc).contentEquals(plain)) { "verify failed" }
                AtomicText.write(f, enc)
                plain.fill(0)
                migrated++
            } catch (e: Exception) {
                failed++
            }
        }
        return Migration(migrated, failed)
    }

    private fun isSafe(alias: String) = alias.isNotEmpty() && alias != "." && alias != ".." &&
        !alias.contains('/') && !alias.contains('\\') && !alias.endsWith(".tmp")
}
