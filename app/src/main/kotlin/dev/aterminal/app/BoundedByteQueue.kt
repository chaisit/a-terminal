package dev.aterminal.app

import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * R4 (F-1/OOM lesson): คิว bytes มีเพดานแทน ConcurrentLinkedQueue — producer ที่เร็วกว่า consumer
 * ต้อง "ชะลอ" (backpressure) ไม่ใช่ "บานจน OOM"
 * (ReentrantLock+Condition ไม่ใช่ monitor wait/notify — Kotlin Any ไม่มี wait()/notifyAll())
 *
 * contract:
 *  - put() = blocking เมื่อ backlog >= [high] (producer ห้ามเป็น main thread — P-ONDATA พิสูจน์แล้ว
 *    ว่า sshd/Termux onData = nio2 thread; Echo = keystroke volume ไม่มีวันชนเพดาน)
 *  - producer ตื่นเมื่อ consumer ลดลง <= [low] (hysteresis high/low — กันปลุกทุก byte)
 *  - pollUpTo() = ไม่ block, ดึงสูงสุด max bytes จากหัว (empty array = ว่าง)
 *  - ห้าม drop byte — เพดาน = block เท่านั้น (byte-exact is sacred)
 *  - clear() ปลุก producer ที่ติด await (backend close → pump EOF ไม่มีวันค้าง)
 */
class BoundedByteQueue(
    private val high: Int = 4 * 1024 * 1024,
    private val low: Int = 2 * 1024 * 1024,
) {
    private val lock = ReentrantLock()
    private val space = lock.newCondition() // ปลุกเมื่อ backlog ต่ำกว่า low หรือ clear()
    private val q = ArrayDeque<ByteArray>()
    private var bytes = 0L

    /** current backlog (ทดสอบ peak water-mark) */
    val sizeBytes: Long get() = lock.withLock { bytes }

    fun put(data: ByteArray) {
        if (data.isEmpty()) return
        lock.withLock {
            while (bytes >= high) space.awaitUninterruptibly()
            q.add(data)
            bytes += data.size
        }
    }

    /** ดึงสูงสุด [max] bytes (empty array = ไม่มีของ — ห้าม block) */
    fun pollUpTo(max: Int): ByteArray {
        lock.withLock {
            if (q.isEmpty() || max <= 0) return ByteArray(0)
            val out = ByteArrayOutputStream(minOf(max, bytes.toInt()))
            while (q.isNotEmpty() && out.size() < max) {
                val head = q.first()
                val take = minOf(head.size, max - out.size())
                out.write(head, 0, take)
                if (take == head.size) q.removeFirst() else q[0] = head.copyOfRange(take, head.size)
                bytes -= take
            }
            if (bytes <= low) space.signalAll() // ปลุก producer ที่ block (hysteresis: ต่ำกว่า low เท่านั้น)
            return out.toByteArray()
        }
    }

    /** ทิ้งของค้าง (controller.close — bytes เก่าของ session ตายไม่ควรถูกรีplay) + ปลุก producer */
    fun clear() {
        lock.withLock {
            q.clear()
            bytes = 0
            space.signalAll()
        }
    }
}
