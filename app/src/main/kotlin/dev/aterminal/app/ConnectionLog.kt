package dev.aterminal.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ring ของ log ที่รอด process death: เขียนต่อท้ายไฟล์ทีละบรรทัด ไม่ fsync (process ถูกฆ่า ข้อมูลยังอยู่ใน kernel)
 * ไฟล์ยาวได้ถึง 2 เท่าของ capacity แล้วค่อยเขียนใหม่ เพื่อไม่ต้อง rewrite ทุกบรรทัด
 */
internal class ConnectionLogBuffer(
    private val file: File,
    private val capacity: Int = 400,
    private val onError: (Throwable) -> Unit = {},
) {
    private val ring = ArrayDeque<String>()
    private var fileLines = 0

    init {
        runCatching {
            if (file.isFile) {
                val old = file.readLines()
                fileLines = old.size
                old.takeLast(capacity).forEach { ring.addLast(it) }
            }
        }.onFailure(onError)
    }

    @Synchronized fun add(line: String) {
        if (ring.size >= capacity) ring.removeFirst()
        ring.addLast(line)
        runCatching {
            if (fileLines >= capacity * 2) {
                AtomicText.write(file, ring.joinToString("\n", postfix = "\n"))
                fileLines = ring.size
            } else {
                file.appendText(line + "\n")
                fileLines++
            }
        }.onFailure(onError)
    }

    @Synchronized fun dump(): String = ring.joinToString("\n")
}

/**
 * log สาเหตุที่ session หลุด สำหรับเครื่องจริงที่ไม่มี adb — user คัดลอกจากเมนูเอง ไม่ส่งออกจากเครื่อง
 * ห้ามใส่ host, user, keystroke, output หรือความลับ: ผู้เรียกส่งเฉพาะ state/code/ชื่อ class (backend redact ข้อความมาแล้ว)
 * เวลาแสดง 3 นาฬิกา: rt-up ที่โตขึ้น = เครื่องหลับลึก, wall ไว้เทียบกับ log ฝั่ง server
 */
object ConnectionLog {
    private const val FILE = "connection-log.txt"
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aterm-connlog").apply { isDaemon = true } }
    @Volatile private var buffer: ConnectionLogBuffer? = null

    fun init(ctx: Context) {
        if (buffer != null) return
        val f = File(ctx.applicationContext.filesDir, FILE)
        // อ่านไฟล์บน io thread: onCreate เรียกตรงนี้บน main (StrictMode/ANR)
        io.execute { if (buffer == null) buffer = ConnectionLogBuffer(f, onError = { Log.w("AtermConn", "log file failed: ${it.javaClass.name}") }) }
    }

    fun d(msg: String) {
        Log.d("AtermConn", msg)
        val line = "${stamp.format(Date())} up=${SystemClock.uptimeMillis()} rt=${SystemClock.elapsedRealtime()} $msg"
        io.execute { buffer?.add(line) }
    }

    /** รอคิวเขียนจบก่อนอ่าน เพื่อให้เห็นบรรทัดล่าสุด เรียกจาก thread ไหนก็ได้ ยกเว้น io thread เอง */
    fun dump(onResult: (String) -> Unit) {
        io.execute { onResult(buffer?.dump().orEmpty()) }
    }
}
