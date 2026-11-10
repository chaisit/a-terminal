package dev.aterminal.backend.ssh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard ของบทเรียน device (H2, Honor 11-10 — build 0.5.13): caller ฝั่ง app เรียก
 * ControlTransport.close() จาก main (WindowStripHost.stop ผ่าน syncStripFocus ตอนสลับ tab) —
 * `ch.close(false)` = graceful flush + channel_close = socket I/O บน caller →
 * StrictMode NetworkOnMainThreadException → MINA ปิด *ทั้ง session* (ทุก channel) →
 * auto-redial = dot แดง→เหลือง→เขียวทุกครั้งที่สลับ tab (log: "session exception
 * android.os.NetworkOnMainThreadException" — repro ได้บน emulator ด้วย tab switch เดียว)
 *
 * บทเรียนเดิมของ resize (SshBackend.resize + resizeExec) ถูกขุดหลุมใหม่กับ control channel —
 * ทุก socket op ต้อง off-caller-thread เสมอ พฤติกรรมจริง pin ด้วย emulator gate (tab switch
 * แล้ว grep logcat) เพราะ JVM unit test ไม่มี StrictMode
 */
class MainThreadIoGuardTest {

    private fun src() = File("src/main/kotlin/dev/aterminal/backend/ssh/SshBackend.kt").readText()

    @Test fun `control channel close dispatches ch-close off the caller thread`() {
        val s = src()
        val marker = "aterm-ssh-control-pump" // anchor: ControlChannel block
        val anchor = s.indexOf(marker)
        assertTrue("ต้องพบ ControlChannel pump (renamed?)", anchor >= 0)
        val start = s.indexOf("override fun close() {", anchor)
        assertTrue("ต้องพบ ControlChannel.close", start >= 0)
        val end = s.indexOf("\n        }", start)
        assertTrue("ต้องปิด body ของ ControlChannel.close", end > start)
        // ตัด comment lines ก่อน — literal ในคอมเมนต์ (เช่น "ch.close(false) = flush...") เคยทำ guard หลุด (11-10)
        val body = s.substring(start, end).lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val dispatch = Regex("""Thread\s*\{|Executor|execute\(""").find(body)
        assertTrue("close() ต้อง dispatch ch.close ไป thread/executor (ห้าม inline — caller = main)", dispatch != null)
        val socketOp = body.indexOf("ch.close(false)")
        assertTrue("body ต้องยังปิด channel จริง", socketOp >= 0)
        assertTrue("การ dispatch ต้องเกิด *ก่อน* ch.close(false)", dispatch!!.range.first < socketOp)
    }

    @Test fun `resize keeps its off-main dispatcher (round-10 lesson, no regression)`() {
        val s = src()
        val start = s.indexOf("override fun resize(cols: Int, rows: Int)")
        assertTrue("ต้องพบ resize", start >= 0)
        val end = s.indexOf("\n    }\n", start)
        val body = s.substring(start, end)
        assertTrue("resize ต้อง enqueue ให้ resizeExec เสมอ (sendWindowChange = socket I/O)",
            body.contains("resizeExec"))
    }
}
