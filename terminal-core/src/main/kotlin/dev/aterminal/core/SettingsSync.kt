package dev.aterminal.core

import java.security.MessageDigest

/**
 * ตรรกะ "ไฟล์ settings เปลี่ยนเองจากข้างนอก" แบบ pure (round 6, reload-on-change)
 * — FileObserver เป็นแค่ตัวปลุก; การตัดสินใจจริงอยู่ที่นี่ (JVM-test ได้, Android-free)
 *
 * ปัญหาที่กัน: ตัวเราเอง persistSettings บ่อย — watcher ต้องไม่ reload ของที่เพิ่งเขียน (loop/กระพริบ)
 * กันด้วย content-hash: คนเขียนล่าสุดจำ sha256 ของข้อความที่เขียน, watcher เฝือไฟล์แล้ว hash ตรง = Ignore
 */
object SettingsSync {

    enum class Decision { Reload, Ignore, Invalid }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * ข้อความที่อ่านได้จากดิสก์ + hash ของครั้งที่ app เขียนล่าสุด (null = app ยังไม่ได้เขียนเองรอบนี้)
     *  - hash ตรง lastWrite → Ignore (self-write)
     *  - parse ไม่ผ่าน → Invalid (caller ต้องคงของเดิมใน memory + toast)
     *  - อื่น ๆ → Reload
     */
    fun decide(diskText: String, lastWriteHash: String?): Decision {
        if (lastWriteHash != null && sha256(diskText) == lastWriteHash) return Decision.Ignore
        return try {
            SettingsLoader.parse(diskText)
            Decision.Reload
        } catch (e: Exception) {
            Decision.Invalid
        }
    }
}
