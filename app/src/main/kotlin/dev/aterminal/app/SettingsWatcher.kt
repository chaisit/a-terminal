package dev.aterminal.app

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * reload-on-change (round 6) — FileObserver เป็น "ตัวปลุก" เท่านั้น:
 *  - debounce 300ms (editor/adb เขียนถี่ = event เดียว)
 *  - callback บน main, แยกอ่านไฟล์ + ตัดสินด้วย SettingsSync.decide (pure, test ใน terminal-core)
 *  - self-write ของเราเองถูกกันด้วย content-hash ที่ persistSettings จำไว้ (ไม่ใช่ ignore flag —
 *    flag พังเมื่อ event มาช้ากว่า write ถัดไป)
 * gate device: ถ้า FileObserver ไม่ยิงจริงบน Android 14 → fallback (บันทึกใน report) =
 * ตรวจ hash อีกรอบทุกครั้งที่เปิด profile list
 */
class SettingsWatcher(private val file: File, private val onChanged: (String) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    @Suppress("DEPRECATION")
    private val observer = object : FileObserver(file.parentFile, CLOSE_WRITE or MOVED_TO or CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != file.name) return
            schedule()
        }
    }

    private fun schedule() {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            val text = runCatching { file.readText() }.getOrNull() ?: return@Runnable
            onChanged(text)
        }
        pending = r
        handler.postDelayed(r, 300)
    }

    /** hook สำรอง (fallback gate): ให้อ่าน+ตรวจทันที ไม่รอ event */
    fun pokeNow() = schedule()

    fun start() = observer.startWatching()
    fun stop() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        observer.stopWatching()
    }
}
