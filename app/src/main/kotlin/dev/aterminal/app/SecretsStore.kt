package dev.aterminal.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ความลับทั้งหมดของ app อยู่ที่นี่ — settings.json ไม่เก็บ credential/pinned key (แนวเดียวกับ WT)
 *  - password: keyed by profile guid
 *  - host key pin (TOFU): keyed by "host:port" -> "algo SHA256:fingerprint"
 *  - private key: ciphertext ใต้ filesDir/keys/<alias> (ดู KeyVault) กุญแจอยู่ใน Android Keystore, settings.json อ้าง alias
 *  - passphrase ของ private key ที่ user เลือกจำ: keyed by alias
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

    /** กุญแจแยกจาก master key ของ secrets.xml: การ reset ไฟล์นั้นต้องไม่ทำให้ private key หาย */
    private val vault by lazy { KeyVault(File(appContext.filesDir, "keys")) { keystoreKey() } }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(VAULT_KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(VAULT_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun importKey(alias: String, content: ByteArray) = vault.store(alias, content)
    fun hasKey(alias: String): Boolean = vault.has(alias)

    /** Keystore ทำงานช้าได้: เรียก off-main ผู้เรียกล้าง array หลังใช้ */
    fun loadKey(alias: String): ByteArray = vault.load(alias)

    internal fun migrateKeys(): KeyVault.Migration = vault.migrate()

    fun deleteKey(alias: String) {
        vault.delete(alias)
        setKeyPassphrase(alias, null)
    }

    fun keyPassphrase(alias: String): String? = prefs.getString("kp:$alias", null)
    fun setKeyPassphrase(alias: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("kp:$alias") else putString("kp:$alias", value) }.apply()
    }

    // fingerprint อยู่กับ SshBackend (ต้อง marshal SSH blob — อย่า duplicate สูตร hash ที่อื่น)

    companion object {
        private const val PREFS = "secrets"
        private const val VAULT_KEY = "aterminal_ssh_keys"

        /** เฉพาะ bad tag = ถอดรหัสไม่ได้แน่ ความผิดพลาดอื่นของ Keystore อาจชั่วคราว ห้ามลบ */
        internal fun isUndecryptable(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(16).any { it is AEADBadTagException }
    }
}
