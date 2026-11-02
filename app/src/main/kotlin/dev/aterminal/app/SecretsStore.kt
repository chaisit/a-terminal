package dev.aterminal.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import javax.crypto.AEADBadTagException

/**
 * ความลับทั้งหมดของ app อยู่ที่นี่ — settings.json ไม่เก็บ credential/pinned key (แนวเดียวกับ WT)
 *  - password: keyed by profile guid
 *  - host key pin (TOFU): keyed by "host:port" -> "algo SHA256:fingerprint"
 *  - private key: ไฟล์จริงใต้ filesDir/keys/<alias> chmod 0600 (settings.json อ้าง alias)
 */
class SecretsStore(context: Context, private val onReset: () -> Unit = {}) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            openPrefs()
        } catch (e: Exception) {
            if (!isUndecryptable(e)) throw e
            // secrets.xml ถูกเข้ารหัสด้วย master key ที่ Keystore เครื่องนี้ไม่มีแล้ว (เช่น ระบบกู้ไฟล์เก่าหลังลงแอปใหม่)
            // อ่านไม่ได้อีกตลอดไป ไม่ลบ = crash ทุกครั้งที่แตะ profile — แจ้ง user เพราะ host pin หายด้วย
            Log.w("AtermSecrets", "secrets undecryptable, resetting", e)
            appContext.deleteSharedPreferences(PREFS)
            onReset()
            openPrefs()
        }
    }

    private fun openPrefs(): SharedPreferences {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun password(guid: String): String? = prefs.getString("pw:$guid", null)
    fun setPassword(guid: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("pw:$guid") else putString("pw:$guid", value) }.apply()
    }

    /** คืน pin เดิม ("algo fp") หรือ null ถ้ายังไม่เคย TOFU */
    fun hostPin(host: String, port: Int): String? = prefs.getString("pin:$host:$port", null)
    fun putHostPin(host: String, port: Int, value: String) {
        prefs.edit().putString("pin:$host:$port", value).apply()
    }

    fun importKey(alias: String, content: ByteArray): File {
        val dir = File(appContext.filesDir, "keys").apply { mkdirs() }
        val f = File(dir, alias)
        f.writeBytes(content)
        f.setReadable(false, false).also { f.setReadable(true, true) }
        f.setWritable(false, false).also { f.setWritable(true, true) }
        return f
    }

    fun keyFile(alias: String): File? {
        val f = File(File(appContext.filesDir, "keys"), alias)
        return if (f.isFile) f else null
    }

    // fingerprint อยู่กับ SshBackend (ต้อง marshal SSH blob — อย่า duplicate สูตร hash ที่อื่น)

    companion object {
        private const val PREFS = "secrets"

        /** เฉพาะ bad tag = ถอดรหัสไม่ได้แน่ ความผิดพลาดอื่นของ Keystore อาจชั่วคราว ห้ามลบ */
        internal fun isUndecryptable(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(16).any { it is AEADBadTagException }
    }
}
