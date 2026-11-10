package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ticket tmux/03: OSC 52 codec ล้วน — wire facts ที่วัดจริงเป็น single source ที่ KDoc ของ
 * [OscClipboard] (ห้าม copy มาซ้ำที่นี่ — จะ drift); coverage ที่นี่: target ว่าง/`;;`, set/query,
 * multi-buffer + comma + อักขระแปลกแบบ tmux-skip, b64 พัง/เกินเพดาน, reply BEL + เพดาน reply
 */
class OscClipboardTest {

    @Test
    fun `decode tmux copy-mode payload with EMPTY selection flag`() {
        // วัดจริง: `ESC]52;;Cgo=BEL` (flags ว่าง) — xterm.js/engine ตัด `52;` ให้ก่อน (วัดจาก bundle:
        // OscHandler._data เริ่มเก็บหลัง `;` แรก) จึงเหลือ `;Cgo=`
        // หมายเหตุ: `Cgo=` = base64 ของ "\n\n" (ตรวจด้วย `printf Cgo= | base64 -d` → 0a0a) — ของจริงบน wire มี two newlines
        val got = OscClipboard.decodePayload(";Cgo=")
        assertEquals(
            OscClipboard.Osc52(
                target = "",
                data = "\n\n".toByteArray(),
                query = false,
            ),
            got,
        )
    }

    @Test
    fun `decode clipboard-cut target c with padded base64`() {
        val got = OscClipboard.decodePayload("c;aGVsbG8=")
        assertEquals(
            OscClipboard.Osc52(
                target = "c",
                data = "hello".toByteArray(),
                query = false,
            ),
            got,
        )
    }

    @Test
    fun `query form decodes to query and keeps target`() {
        assertEquals(
            OscClipboard.Osc52(target = "c", data = ByteArray(0), query = true),
            OscClipboard.decodePayload("c;?"),
        )
        // tmux 3.4 input_osc_52 รับ `?` เป็น payload — ฝั่ง client ก็ต้องไม่แปลเป็น write ว่าง
        // (wire `52;;?` หลังตัด `52;` = `;?` — target ว่าง)
        assertEquals(
            OscClipboard.Osc52(target = "", data = ByteArray(0), query = true),
            OscClipboard.decodePayload(";?"),
        )
    }

    @Test
    fun `multi-buffer target keeps the full flag string`() {
        // `0cp` = buffer 0 + clipboard + primary; app เก็บ target ไว้ตามที่ส่งมา (ไม่ normalize)
        assertEquals(
            OscClipboard.Osc52(
                target = "0cp",
                data = "hi".toByteArray(),
                query = false,
            ),
            OscClipboard.decodePayload("0cp;aGk="),
        )
    }

    @Test
    fun `comma-combined clipboard target survives - tmux skips unknown chars, we must not drop it`() {
        // xterm เขียนแบบ `c,p`; tmux 3.6 input_osc_52 (วัด source) แค่ข้ามอักขระนอก allow list — ห้าม reject ทั้ง payload
        val osc = OscClipboard.decodePayload("c,p;aGk=")!!
        assertEquals("c,p", osc.target)
        assertEquals("hi", String(osc.data, Charsets.UTF_8))
    }

    @Test
    fun `payload must be valid base64 or the whole sequence is dropped`() {
        // tmux 3.4 ก็ drop (b64_pton == -1 → return) — ห้ามส่ง garbage เข้า clipboard user
        assertNull(OscClipboard.decodePayload("c;!!!not base64!!!"))
        assertNull(OscClipboard.decodePayload("c"))       // ไม่มี `;` = ไม่มี payload
        assertNull(OscClipboard.decodePayload("c;"))      // payload ว่าง = ไม่ใช่ write (และไม่ใช่ query)
        // tmux 3.6 (วัด source) ข้ามอักขระนอก allow list ไม่ใช่ reject → `z` ถูกกรอง, write อยู่รอด (parity)
        assertEquals(OscClipboard.Osc52("", "hi".toByteArray(), false), OscClipboard.decodePayload("z;aGk="))
        assertNull(OscClipboard.decodePayload("c;aGk=\u0000x")) // raw NUL ใน b64 = ไม่ใช่ base64
    }

    @Test
    fun `decode tolerates missing padding and rejects oversized payloads`() {
        // b64_ntop ของ tmux มี padding เสมอ แต่ xterm client อื่นบางตัวไม่มี — ยืดหยุ่นได้ (decode ได้ = ส่งต่อ)
        assertEquals(
            OscClipboard.Osc52("c", "hello".toByteArray(), false),
            OscClipboard.decodePayload("c;aGVsbG8"),
        )
        val big = "A".repeat(OscClipboard.MAX_B64_CHARS + 4)
        assertNull(OscClipboard.decodePayload("c;$big")) // clipboard bomb — กัน heap ฝั่ง app
    }

    @Test
    fun `unknown target chars are skipped like tmux and buffers 8-9 are valid`() {
        // tmux buffer set = 0-9 (codec เก่าเผลอปิด 8/9 ด้วย `0-7`); junk กลาง string = ข้ามตัวนั้น ไม่ใช่ทิ้งทั้งก้อน
        assertEquals("c,9", OscClipboard.decodePayload("cX,9;aGk=")!!.target)
        assertEquals("8", OscClipboard.decodePayload("8;aGk=")!!.target)
    }

    @Test
    fun `encodeReply uses BEL terminator and echoes the requested target`() {
        // terminfo `Ms=\E]52;%p1%s;%p2%s\7` ที่ tmux ใช้ลงท้าย BEL; client ตอบกลับจึงใช้รูปเดียวกัน
        assertEquals("\u001b]52;c;aGVsbG8=\u0007", OscClipboard.encodeReply("c", "hello".toByteArray()))
        assertEquals("\u001b]52;;\u0007", OscClipboard.encodeReply("", ByteArray(0)))
    }

    @Test
    fun `oversized clipboard gets no reply instead of a pty bomb`() {
        // reply = ขาเขียนเข้า transport — กัน symmetric กับ MAX_B64_CHARS ฝั่งรับ
        assertNotNull(OscClipboard.encodeReply("c", ByteArray(OscClipboard.MAX_REPLY_BYTES)))
        assertNull(OscClipboard.encodeReply("c", ByteArray(OscClipboard.MAX_REPLY_BYTES + 1)))
    }
}
