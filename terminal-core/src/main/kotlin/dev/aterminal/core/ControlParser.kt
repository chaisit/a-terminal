package dev.aterminal.core

/**
 * ticket tmux/09: parser ของ control-mode stream (`tmux -CC attach` บน exec-PTY channel) — incremental, stateless-per-feed
 *
 * wire facts (spike 11-08 บน tmux 3.6 ทั้ง local และ lab t03 — หลักฐานเต็มในตั๋ว §Notes):
 *  - stream เปิดด้วย DCS `\eP1000p` ติดกับ %begin แรก (บรรทัดเดียวกัน)
 *  - `%begin|%end|%error <time> <number> <flag>` — flag = CMDQ_STATE_CONTROL (3.6), ไม่ใช่ checksum → pair ด้วย (time,number)
 *  - notification ไม่มีทางเกิดภายใน output block (man CONTROL MODE) → บรรทัดไม่ขึ้นต้น `%` ข้างนอก block = ขยะ/echo, ข้าม
 *  - channel เป็น PTY: ท้ายบรรทัด `\r\n`; UTF-8 multibyte อาจขาดกลาง chunk → buffer เป็น bytes, decode เฉพาะบรรทัดที่ครบ
 *
 * threading: caller เดียวเรียก feed เรียงลำดับ (host = main) — ไม่มี synchronization
 */
class ControlParser {

    private val pending = java.io.ByteArrayOutputStream()

    private var inBlock = false // ระหว่าง %begin … %end/%error: ทุกบรรทัด (รวมว่าง/หน้า%-) เป็น content — man: notification ห้ามเกิดใน block

    fun feed(chunk: ByteArray): List<ControlEvent> {
        val out = mutableListOf<ControlEvent>()
        pending.write(chunk)
        val all = pending.toByteArray()
        var start = 0
        for (i in all.indices) {
            if (all[i] == '\n'.code.toByte()) {
                var end = i
                if (end > start && all[end - 1] == '\r'.code.toByte()) end--
                // บรรทัดว่าง: ใน block = content จริง (capture/ls มี empty line ได้), นอก block = noise/detach-echo = ข้าม
                if (end > start || inBlock) out += line(String(all, start, end - start, Charsets.UTF_8))
                start = i + 1
            }
        }
        pending.reset()
        if (start < all.size) pending.write(all, start, all.size - start)
        return out
    }

    private fun line(raw: String): List<ControlEvent> {
        if (inBlock) {
            FRAME.matchEntire(raw)?.let { m ->
                val (kind, time, number, flag) = m.destructured
                inBlock = false
                return listOf(frameOf(kind, time, number, flag))
            }
            return listOf(ControlEvent.BlockLine(raw))
        }
        // DCS control-mode marker มาติดกับ %begin แรก (` ESC P1000p%begin …`) — ปลดออกแล้ว emit marker ก่อน
        if (raw.startsWith(DCS_PREFIX)) {
            return listOf(ControlEvent.ControlModeMarker) + parseLine(raw.substring(DCS_PREFIX.length))
        }
        return parseLine(raw)
    }

    private fun frameOf(kind: String, time: String, number: String, flag: String): ControlEvent {
        val (t, n, f) = Triple(time.toLong(), number.toInt(), flag.toInt())
        return when (kind) {
            "begin" -> { inBlock = true; ControlEvent.Begin(t, n, f) }
            "end" -> ControlEvent.End(t, n, f)
            else -> ControlEvent.BlockError(t, n, f)
        }
    }

    private fun parseLine(line: String): List<ControlEvent> {
        if (!line.startsWith("%")) return emptyList() // pty echo/ขยะ — ignore โดยโครงสร้าง
        FRAME.matchEntire(line)?.let { m ->
            val (kind, time, number, flag) = m.destructured
            return listOf(frameOf(kind, time, number, flag))
        }
        OUTPUT.matchEntire(line)?.let { m ->
            val (pane, data) = m.destructured
            return listOf(ControlEvent.Output(pane, decodeOctal(data)))
        }
        EXTENDED.matchEntire(line)?.let { m ->
            val (pane, age, data) = m.destructured
            // age = ค่า opaque จาก server (observed 0..n บน 3.6 — หนึ่งหน่วยยังไม่ lock, ห้ามตีความ)
            return listOf(ControlEvent.ExtendedOutput(pane, age.toLong(), decodeOctal(data)))
        }
        if (line.startsWith("%pause ")) return listOf(ControlEvent.Pause(line.substring(7)))
        if (line.startsWith("%continue ")) return listOf(ControlEvent.Continue(line.substring(10)))
        if (line == "%exit") return listOf(ControlEvent.Exit(null))
        if (line.startsWith("%exit ")) return listOf(ControlEvent.Exit(line.substring(6)))
        if (line.startsWith("%session-changed ")) {
            val rest = line.substring("%session-changed ".length)
            val sp = rest.indexOf(' ')
            if (sp > 0) return listOf(ControlEvent.SessionChanged(rest.substring(0, sp), rest.substring(sp + 1)))
        }
        SUBSCRIPTION.matchEntire(line)?.let { m ->
            val (name, session, window, index, pane, value) = m.destructured
            // pane token "-" = subscription ระดับ window (ไม่มี pane); token ก่อน ':' ที่ไม่ใช่ "-"/%n = future-use (man) — regex เรา lock เฉพาะรูปที่วัดจริง
            return listOf(ControlEvent.SubscriptionChanged(name, session, window, index.toInt(), pane.takeUnless { it == "-" }, value))
        }
        if (line == "%sessions-changed") return listOf(ControlEvent.SessionsChanged)
        if (line.startsWith("%session-window-changed ")) {
            val rest = line.substring("%session-window-changed ".length).split(' ')
            if (rest.size == 2) return listOf(ControlEvent.SessionWindowChanged(rest[0], rest[1]))
        }
        for ((prefix, make) in WINDOW_ID_ONLY) {
            if (line.startsWith(prefix)) {
                val id = line.substring(prefix.length)
                if (id.isNotEmpty() && !id.contains(' ')) return listOf(make(id))
            }
        }
        if (line.startsWith("%window-renamed ")) {
            val rest = line.substring("%window-renamed ".length)
            val sp = rest.indexOf(' ')
            if (sp > 0) return listOf(ControlEvent.WindowRenamed(rest.substring(0, sp), rest.substring(sp + 1))) // name มี space ได้ = rest ทั้งก้อน
        }
        // forward-compat (ticket Notes ข้อ 12): `%name args` ที่ไม่รู้จัก = ส่งขึ้น caller เป็น Unknown, ห้ามหาย/ห้าม break stream
        UNKNOWN.matchEntire(line)?.let { m ->
            val (name, args) = m.destructured
            return listOf(ControlEvent.Unknown(name, args))
        }
        return emptyList()
    }

    /** escape rule จาก control_append_data @3.6: ทุก byte <0x20 หรือ '\\' → "\\%03o" (3 octal digits เสมอ), ที่เหลือ raw — decode: `\NNN` → byte, อื่น = literal */
    private fun decodeOctal(data: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.length) {
            val c = data[i]
            if (c == '\\' && i + 3 < data.length &&
                data[i + 1] in '0'..'7' && data[i + 2] in '0'..'7' && data[i + 3] in '0'..'7'
            ) {
                out.write((data[i + 1] - '0') * 64 + (data[i + 2] - '0') * 8 + (data[i + 3] - '0'))
                i += 4
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8)) // non-ASCII raw (UTF-8 ผ่านไม่ถูก escape)
                i++
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val DCS_PREFIX = "\u001bP1000p" // ESC P … — marker ที่ control.c เขียนเมื่อ -CC
        private val FRAME = Regex("^%(begin|end|error) (\\d+) (\\d+) (\\d+)$")
        private val OUTPUT = Regex("^%output (%\\S+) (.*)$")
        private val EXTENDED = Regex("^%extended-output (%\\S+) (\\d+) : (.*)$")
        private val UNKNOWN = Regex("^%([a-z][a-z0-9-]*)(?: (.*))?$")

        // รูปที่วัดจริง 11-08 ทั้ง what=@* (pane "-") และ what=%* (pane "%n") — ค่า subscription re-expand ≤1/s
        private val SUBSCRIPTION = Regex("^%subscription-changed (\\S+) (\\S+) (\\S+) (\\d+) (\\S+) : (.*)$")

        // notification รูป `%<name> <window-id>` เดี่ยว (man CONTROL MODE) — table แทน if-chain (same shape, ข้อมูล)
        // gotcha วัดจริง 11-09: kill window ของ session ที่ attach = tmux ส่ง `%unlinked-window-close` ไม่ใช่ `%window-close`
        // (client ทั้ง linked/unlinked ต้อง trim ได้ทั้งคู่ — ดู WindowStrip.trimClosed)
        private val WINDOW_ID_ONLY: Map<String, (String) -> ControlEvent> = mapOf(
            "%window-add " to { ControlEvent.WindowAdd(it) },
            "%window-close " to { ControlEvent.WindowClose(it) },
            "%unlinked-window-add " to { ControlEvent.UnlinkedWindowAdd(it) },
            "%unlinked-window-close " to { ControlEvent.UnlinkedWindowClose(it) },
            "%unlinked-window-renamed " to { ControlEvent.UnlinkedWindowRenamed(it) }, // man 3.6: id เท่านั้น (ต่าง %window-renamed ที่มี name)
        )
    }
}

/**
 * events ที่ parser คืน — ตรงตาม notification ที่ spike เจอจริง (subset ของ man CONTROL MODE);
 * cycle ถัดไปจะเพิ่ม output/subscription/window events + Unknown (forward-compat)
 */
sealed interface ControlEvent {
    object ControlModeMarker : ControlEvent
    data class Begin(val time: Long, val number: Int, val flag: Int) : ControlEvent
    data class End(val time: Long, val number: Int, val flag: Int) : ControlEvent
    data class BlockError(val time: Long, val number: Int, val flag: Int) : ControlEvent
    /** หนึ่งบรรทัดเนื้อหาภายใน block (รวมบรรทัดว่างและบรรทัดที่หน้าตาเหมือน notification — man: notification ห้ามเกิดใน block) */
    data class BlockLine(val text: String) : ControlEvent
    /** pane bytes — data = octal-unescaped แล้ว (control_append_data rule) */
    data class Output(val paneId: String, val data: ByteArray) : ControlEvent {
        // data class + ByteArray = equals เชิง reference → lock content equality (test + dedupe ฝั่ง caller)
        override fun equals(other: Any?): Boolean =
            other is Output && paneId == other.paneId && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * paneId.hashCode() + data.contentHashCode()
    }

    data class ExtendedOutput(val paneId: String, val age: Long, val data: ByteArray) : ControlEvent {
        override fun equals(other: Any?): Boolean =
            other is ExtendedOutput && paneId == other.paneId && age == other.age && data.contentEquals(other.data)

        override fun hashCode(): Int = (31 * (31 * paneId.hashCode() + age.hashCode()) + data.contentHashCode())
    }
    data class Pause(val paneId: String) : ControlEvent
    data class Continue(val paneId: String) : ControlEvent
    data class SessionChanged(val sessionId: String, val sessionName: String) : ControlEvent
    data class SessionWindowChanged(val sessionId: String, val windowId: String) : ControlEvent
    object SessionsChanged : ControlEvent
    data class WindowAdd(val windowId: String) : ControlEvent
    data class WindowClose(val windowId: String) : ControlEvent
    data class UnlinkedWindowAdd(val windowId: String) : ControlEvent
    data class UnlinkedWindowClose(val windowId: String) : ControlEvent
    data class UnlinkedWindowRenamed(val windowId: String) : ControlEvent
    data class WindowRenamed(val windowId: String, val name: String) : ControlEvent
    /** client จบ — app ต้องถือว่า control channel ตาย (reason = null เมื่อ tmux ไม่แจ้ง) */
    data class Exit(val reason: String?) : ControlEvent
    /** notification ที่ parser ไม่รู้จัก (tmux รุ่นใหม่กว่า parser) — ส่งขึ้นบน ห้ามหาย (forward-compat) */
    data class Unknown(val notification: String, val args: String) : ControlEvent
    /** subscription-level value: what=@* → paneId = null; what=%* → "%n" (spike 11-08) */
    data class SubscriptionChanged(
        val name: String,
        val sessionId: String,
        val windowId: String,
        val index: Int,
        val paneId: String?,
        val value: String,
    ) : ControlEvent
}
