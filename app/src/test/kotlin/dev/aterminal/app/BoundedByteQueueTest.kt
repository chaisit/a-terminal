package dev.aterminal.app

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// R4 (F-1): เพดานคิว + zero-loss — gate "flood 50MB vs consumer ช้า → peak ≤ HIGH, sha256 in==out"
class BoundedByteQueueTest {

    @Test fun flood50MB_peakBounded_zeroLoss() {
        val high = 1 shl 20 // 1MB (scale เล็กเพื่อความเร็ว test — semantics เดียวกับ 4MB จริง)
        val q = BoundedByteQueue(high, high / 2)
        val total = 50L * 1024 * 1024
        val chunk = ByteArray(32 * 1024) { (it % 251).toByte() }
        val din = MessageDigest.getInstance("SHA-256")
        val dout = MessageDigest.getInstance("SHA-256")
        // peak/received/error อ่านหลัง join() = happens-before ครบ ไม่ต้อง volatile
        var peak = 0L
        var received = 0L
        var error: Throwable? = null

        val producer = Thread {
            try {
                var sent = 0L
                while (sent < total) {
                    q.put(chunk) // จะ block เมื่อ backlog ≥ high — จุด proof
                    din.update(chunk)
                    sent += chunk.size
                    peak = maxOf(peak, q.sizeBytes)
                }
            } catch (t: Throwable) { error = t }
        }.apply { isDaemon = true; start() }

        val consumer = Thread {
            try {
                while (received < total) {
                    val b = q.pollUpTo(256 * 1024)
                    if (b.isEmpty()) Thread.sleep(1)
                    else { dout.update(b); received += b.size }
                }
            } catch (t: Throwable) { error = t }
        }.apply { isDaemon = true; start() }

        producer.join(30_000); consumer.join(30_000)
        assertTrue("producer alive = put block ไม่ตื่น (deadlock)", !producer.isAlive)
        assertTrue("consumer alive = ไม่ drain ครบ", !consumer.isAlive)
        error?.let { throw it }
        // put เช็ค bytes<high ก่อนเพิ่ม 1 chunk ⇒ เพดาน = high-1 + chunk
        assertTrue("peak=${peak / 1024}KB ต้อง ≤ high+chunk", peak <= high + chunk.size)
        assertEquals(total, received)
        assertArrayEquals("sha256 in != out = byte หาย/กลับลำดับ", din.digest(), dout.digest())
    }

    @Test fun putBlocksAtHigh_andWakesBelowLow() {
        val q = BoundedByteQueue(64 * 1024, 16 * 1024)
        q.put(ByteArray(64 * 1024)) // เต็ม high พอดี
        var done = false
        val p = Thread { q.put(ByteArray(8 * 1024)); done = true }.apply { isDaemon = true; start() }
        Thread.sleep(150)
        assertTrue("put ต้อง block ที่ high", !done)
        q.pollUpTo(48 * 1024) // ลง 16KB = low ⇒ notify
        p.join(2_000)
        assertTrue("producer ต้องตื่นหลัง poll ต่ำกว่า low", done)
    }

    @Test fun pollSplitsChunk_andClearReleasesProducer() {
        val q = BoundedByteQueue(1024 * 1024, 512 * 1024)
        q.put(ByteArray(1024) { it.toByte() })
        val first = q.pollUpTo(300)
        assertEquals(300, first.size)
        val rest = q.pollUpTo(4096)
        assertEquals(724, rest.size)
        assertEquals(0, q.pollUpTo(4096).size) // ว่าง = empty ไม่ใช่ block
        // clear ปลุก producer ที่ติด wait (session close path)
        val big = BoundedByteQueue(64, 32)
        big.put(ByteArray(64))
        val p = Thread { big.put(ByteArray(8)) }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        assertTrue(p.isAlive)
        big.clear()
        p.join(2_000)
        assertTrue("clear() ต้องไม่ปล่อย producer ค้าง", !p.isAlive)
    }
}
