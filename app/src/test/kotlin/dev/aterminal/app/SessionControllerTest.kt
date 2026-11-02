package dev.aterminal.app

import dev.aterminal.core.BackendError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.aterminal.core.CloseReason
import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TerminalBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * state machine ของ SessionController บน JVM ล้วน (inject evalJs + ioDispatcher — ไม่ต้องมี WebView/Robolectric)
 * regression หลัก (crash round 2): หลัง connect fail / remote close → `write()` ต้อง no-op ไม่ throw,
 * เพราะ SshBackend.write บน channel ตาย จะ throw BackendError กลาง main thread = app ตาย
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {

    private class FakeBackend(
        var failConnect: Throwable? = null,
        var writeThrows: Throwable? = null,
    ) : TerminalBackend {
        override val type = "fake"
        override var onData: (ByteArray) -> Unit = {}
        override var onClosed: (CloseReason) -> Unit = {}
        var writes = 0
        var closed = false
        val resizes = mutableListOf<Pair<Int, Int>>() // R9: coalescer assertions
        override suspend fun connect(startup: StartupArgs) {
            failConnect?.let { throw it }
            onData("hi\r\n".toByteArray()) // drain path ต้องทำงานแม้ queue ว่าง
        }
        override fun write(data: ByteArray) {
            writes++
            writeThrows?.let { throw it }
        }
        override fun resize(cols: Int, rows: Int) { resizes += cols to rows }
        override fun close() { closed = true; onClosed(CloseReason.Normal) }
        fun hangUp() = onClosed(CloseReason.Error("server hung up", true))
    }

    private val startup = StartupArgs(null, null, 80, 24)

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, js: (String) -> Unit = {}) =
        SessionController(js, scope, { _, _ -> }, StandardTestDispatcher((scope as kotlinx.coroutines.test.TestScope).testScheduler))

    @Test fun connectFailure_nullsBackend_andWriteIsSilent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = controller(this)
            val be = FakeBackend(failConnect = BackendError(BackendError.Code.AUTH_FAILED, "bad password"))
            ctrl.connect(be, startup)
            advanceUntilIdle()
            assertEquals(SessionController.State.DISCONNECTED, ctrl.state)
            assertNull(ctrl.activeBackend)
            ctrl.write("ls\r")                 // ← จุดที่ app ตายจริงบน device (round 2)
            ctrl.write(byteArrayOf(0x03))
            assertEquals(0, be.writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun connectSuccess_connected_andWriteForwards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val js = mutableListOf<String>()
            val ctrl = controller(this) { js.add(it) }
            val be = FakeBackend()
            ctrl.connect(be, startup)
            advanceUntilIdle()
            assertEquals(SessionController.State.CONNECTED, ctrl.state)
            ctrl.write("ls\r")
            assertEquals(1, be.writes)
            assertEquals(SessionController.State.CONNECTED, ctrl.state)
            // output path: onData → drain → Aterm.onBytes (base64 ของ "hi\r\n")
            assertEquals(true, js.any { it.contains("Aterm.onBytes") })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun remoteClose_nullsBackend_andWriteIsSilent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = controller(this)
            val be = FakeBackend()
            ctrl.connect(be, startup)
            advanceUntilIdle()
            be.hangUp()
            advanceUntilIdle()
            assertEquals(SessionController.State.DISCONNECTED, ctrl.state)
            assertNull(ctrl.activeBackend)
            ctrl.write("ls\r")
            assertEquals(0, be.writes) // ห้ามส่งอะไรเข้า backend ที่ตายแล้ว
        } finally { Dispatchers.resetMain() }
    }

    @Test fun writeThrowing_midSession_transitionsToDisconnected() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = controller(this)
            val be = FakeBackend()
            ctrl.connect(be, startup)
            advanceUntilIdle()
            be.writeThrows = IllegalStateException("channel is closed") // พฤติกรรมจริงของ MINA หลัง channel ตาย
            ctrl.write("ls\r") // caller ต้องไม่ได้รับ exception
            advanceUntilIdle()
            assertEquals(SessionController.State.DISCONNECTED, ctrl.state)
            assertNull(ctrl.activeBackend)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun close_isIdempotent_andGoesIdle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = controller(this)
            val be = FakeBackend()
            ctrl.connect(be, startup)
            advanceUntilIdle()
            ctrl.close()
            ctrl.close()
            advanceUntilIdle()
            assertEquals(SessionController.State.IDLE, ctrl.state)
            assertNull(ctrl.activeBackend)
        } finally { Dispatchers.resetMain() }
    }

    // R3 (F-1 lesson): nativeSink/engine throw (เช่น OOM กลาง flood) = ต้อง map เป็น DISCONNECTED
    // ไม่ใช่ kill drain loop/app — และ loop ต้องหยุด (batch ตามหลังไม่ถูกรี-throw ซ้ำ)
    @Test fun nativeSinkThrowing_disconnects_andLoopStops() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var armed = true
            val sink: (ByteArray) -> Unit = { if (armed) throw OutOfMemoryError("flood test") }
            val ctrl = SessionController({}, this, { _, _ -> }, StandardTestDispatcher(testScheduler), sink)
            val be = FakeBackend()
            ctrl.connect(be, startup) // connect ปล่อย onData("hi") → batch แรก = ระเบิด
            advanceUntilIdle()
            assertEquals(SessionController.State.DISCONNECTED, ctrl.state)
            assertNull(ctrl.activeBackend)
            armed = false
            be.onData("late\r\n".toByteArray()) // queue เข้ามาใหม่ — loop ตายแล้ว ต้องไม่มี crash/สถานะเปลี่ยนซ้ำ
            advanceUntilIdle()
            assertEquals(SessionController.State.DISCONNECTED, ctrl.state)
        } finally { Dispatchers.resetMain() }
    }

    // ---- R9: resize coalescer (fold/drag = 60 resize/s ห้ามถึง backend ทุกครั้ง) ----
    // clock = virtual time ของ scheduler → delay(trailing) กับ clock ตรงกัน deterministic

    private fun coalescerCtrl(scope: kotlinx.coroutines.test.TestScope) = SessionController(
        {}, scope, { _, _ -> }, StandardTestDispatcher(scope.testScheduler), null,
        nanoClock = { scope.testScheduler.currentTime * 1_000_000L }, // virtual ms → ns
    )

    @Test fun resizeBurst_firstImmediate_plusOneTrailingLatest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = coalescerCtrl(this)
            val be = FakeBackend()
            ctrl.connect(be, startup); advanceUntilIdle()
            ctrl.resize(80, 24); ctrl.resize(79, 24); ctrl.resize(40, 30) // burst ณ t=0 (fold cycle)
            assertEquals(listOf(80 to 24), be.resizes)                    // first-immediate
            advanceTimeBy(120); runCurrent()
            assertEquals(listOf(80 to 24, 40 to 30), be.resizes)          // trailing = pair ล่าสุด ครั้งเดียว
            assertEquals(40 to 30, ctrl.pendingResize)
            advanceUntilIdle()
            assertEquals(2, be.resizes.size)                              // ไม่มี shot ซ้ำหลัง burst จบ
        } finally { Dispatchers.resetMain() }
    }

    @Test fun resizeSpaced_everySendPassesThrough() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = coalescerCtrl(this)
            val be = FakeBackend()
            ctrl.connect(be, startup); advanceUntilIdle()
            ctrl.resize(80, 24)
            advanceTimeBy(150); runCurrent()                              // ผ่าน gap → รอบถัดไป immediate
            ctrl.resize(60, 20)
            advanceUntilIdle()
            assertEquals(listOf(80 to 24, 60 to 20), be.resizes)          // ไม่มี trailing เกิน
            ctrl.resize(40, 30)                                           // burst เล็กท้าย → trailing 1 shot
            advanceUntilIdle()
            assertEquals(listOf(80 to 24, 60 to 20, 40 to 30), be.resizes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun resizeBeforeConnect_isNoop_andCloseKillsTrailing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val ctrl = coalescerCtrl(this)
            val be = FakeBackend()
            ctrl.resize(80, 24)                                    // IDLE = เงียบ (พฤติกรรมเดิม)
            assertEquals(0, be.resizes.size)
            ctrl.connect(be, startup); advanceUntilIdle()
            ctrl.resize(80, 24); ctrl.resize(40, 30)               // มี trailing ค้างอยู่
            ctrl.close()
            advanceUntilIdle()
            assertEquals(listOf(80 to 24), be.resizes)             // trailing ห้ามยิงหลัง close
        } finally { Dispatchers.resetMain() }
    }

    @Test fun jsQuote_escapes_quotes_backslash_and_control_chars() {
        assertEquals("\"\"", SessionController.jsQuote(""))
        assertEquals("\"a\\\"b\\\\c\\n\\u001b\"", SessionController.jsQuote("a\"b\\c\n\u001B"))
        assertEquals("\"ไทย ✓\"", SessionController.jsQuote("ไทย ✓"))
    }
}
