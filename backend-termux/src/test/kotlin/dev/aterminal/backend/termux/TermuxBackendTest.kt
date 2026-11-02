package dev.aterminal.backend.termux

import dev.aterminal.core.BackendError
import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** backend fake บันทึกทุก call — ตรวจ delegation ของ TermuxBackend (JVM ล้วน ไม่มี Android) */
private class FakeBackend : TerminalBackend {
    override val type = "ssh-fake"
    override var onData: (ByteArray) -> Unit = {}
    override var onClosed: (CloseReason) -> Unit = {}
    val written = ByteArrayOutputStream()
    var resized: Pair<Int, Int>? = null
    var closed = false
    var connectThrew: Exception? = null
    var connectCount = 0

    override suspend fun connect(startup: StartupArgs) {
        connectCount++
        connectThrew?.let { throw it }
    }

    override fun write(data: ByteArray) { written.write(data) }
    override fun resize(cols: Int, rows: Int) { resized = cols to rows }
    override fun close() { closed = true }
}

class TermuxBackendTest {

    private val startup = StartupArgs(commandline = null, startingDirectory = null, cols = 80, rows = 24)

    @Test
    fun `ไม่มี factory = LOCAL_SERVICE_MISSING ตาม contract เดิม`() = runBlocking {
        val b = TermuxBackend()
        try {
            b.connect(startup)
            throw AssertionError("ต้อง throw")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.LOCAL_SERVICE_MISSING, e.code)
        }
    }

    @Test
    fun `factory ถูกเรียกด้วย 127001 + port ค่า default 8022 แล้ว delegate connect`() = runBlocking {
        var seen: Pair<String, Int>? = null
        val fake = FakeBackend()
        val b = TermuxBackend(factory = { h, p -> seen = h to p; fake })
        b.connect(startup)
        assertEquals("127.0.0.1" to 8022, seen)
        assertEquals(1, fake.connectCount)
        assertEquals("termux", b.type)
    }

    @Test
    fun `onData และ onClosed forward จาก delegate`() = runBlocking {
        val fake = FakeBackend()
        val b = TermuxBackend(sshPort = 9022, factory = { _, _ -> fake })
        val got = ArrayList<ByteArray>()
        val closed = ArrayList<CloseReason>()
        b.onData = { got.add(it) }
        b.onClosed = { closed.add(it) }
        b.connect(startup)
        fake.onData(byteArrayOf(1, 2, 3))
        fake.onClosed(CloseReason.Error("boom", true))
        assertEquals(1, got.size)
        assertTrue(got[0].contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(CloseReason.Error("boom", true), closed[0])
    }

    @Test
    fun `write resize delegate จริง หลัง connect, ก่อน connect = CHANNEL_FAILED`() = runBlocking {
        val fake = FakeBackend()
        val b = TermuxBackend(factory = { _, _ -> fake })
        try {
            b.write(byteArrayOf(7))
            throw AssertionError("ต้อง throw ก่อน connect")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.CHANNEL_FAILED, e.code)
        }
        b.connect(startup)
        b.write(byteArrayOf(104, 105))
        b.resize(100, 30)
        assertEquals("hi", String(fake.written.toByteArray()))
        assertEquals(100 to 30, fake.resized)
    }

    @Test
    fun `connect พัง = ล้าง delegate, close ซ้ำ = silent`() = runBlocking {
        val fake = FakeBackend().apply { connectThrew = BackendError(BackendError.Code.UNREACHABLE, "down") }
        val b = TermuxBackend(factory = { _, _ -> fake })
        try {
            b.connect(startup)
            throw AssertionError("ต้อง throw")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.UNREACHABLE, e.code)
        }
        try {
            b.write(byteArrayOf(1))
            throw AssertionError("delegate ต้องถูกล้าง")
        } catch (e: BackendError) {
            assertEquals(BackendError.Code.CHANNEL_FAILED, e.code)
        }
        var fired = 0
        b.onClosed = { fired++ }
        b.close()                          // never connected → silent, ไม่ยิง onClosed
        assertEquals(0, fired)

        fake.connectThrew = null
        b.connect(startup)                 // retry จนติด
        b.close()
        assertTrue(fake.closed)
        assertEquals(1, fired)             // Normal หนึ่งครั้ง
        b.close()                          // close ซ้ำ = silent, delegate ถูก-consuming แล้ว
        assertEquals(1, fired)
    }
}
