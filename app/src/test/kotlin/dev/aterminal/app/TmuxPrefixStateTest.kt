package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ticket tmux/05: prefix-pending state machine (pure, Q3=buffer — **ห้ามมี byte ค้างใน PTY**)
 *
 * contract ที่ test นี้คุม (grill 11-05, user: "ตามแตะนำทุกข้อ"):
 *  - tap = armed อย่างเดียว (ยังไม่ส่ง byte) — input ถัดไปจาก funnel ใดก็ได้ = prefix + input พร้อมกัน
 *  - cancel ทุกทาง (tap ซ้ำ = literal, timeout, pane switch, dial) = ไม่เคยมี byte ค้าง
 *  - timeout = token-guarded (timeout ของ pending รุ่นเก่า ห้ามล้าง pending รุ่นใหม่)
 *  - fail-soft แบบ modifier: multi-char (paste/composition) = ทิ้ง prefix, ส่ง input ตรง (ห้าม drop input)
 *  - escape sequence (ลูกศร CSI) = single "key" → ต่อ prefix ได้
 */
class TmuxPrefixStateTest {

    private fun state(prefix: String = "C-b") = TmuxPrefixState().apply {
        setPrefix(TmuxPrefixState.parseSpec(prefix) ?: error("bad spec in test"))
    }

    // ---- parseSpec / default ----

    @Test
    fun `parseSpec accepts C-letter both cases and gives control byte`() {
        assertEquals(0x01.toByte(), TmuxPrefixState.parseSpec("C-a"))
        assertEquals(0x02.toByte(), TmuxPrefixState.parseSpec("C-b"))
        assertEquals(0x1A.toByte(), TmuxPrefixState.parseSpec("C-Z"))
    }

    @Test
    fun `parseSpec rejects anything else and null means default C-b`() {
        assertNull(TmuxPrefixState.parseSpec("C-1")) // ไม่ใช่ control-letter = reject (toast ที่ form)
        assertNull(TmuxPrefixState.parseSpec("A"))
        assertNull(TmuxPrefixState.parseSpec("C-"))
        assertNull(TmuxPrefixState.parseSpec(""))
        assertEquals(0x02.toByte(), TmuxPrefixState.DEFAULT_PREFIX)
    }

    @Test
    fun `override prefix is used by all send paths`() {
        val s = state("C-a")
        s.arm()
        assertEquals("\u0001b", s.transform("b")) // prefix C-a + 'b'
    }

    // ---- transform (funnel) ----

    @Test
    fun `not armed = input passes untouched (plain mode unchanged)`() {
        val s = state()
        assertEquals("ls\r", s.transform("ls\r"))
        assertEquals("a", s.transform("a"))
    }

    @Test
    fun `armed then single key = prefix and key go together, pending ends`() {
        val s = state()
        s.arm()
        assertEquals("\u0002w", s.transform("w"))
        assertFalse(s.isArmed())
        assertEquals("w", s.transform("w")) // ครั้งถัดไป = ปกติแล้ว
    }

    @Test
    fun `armed then CSI sequence = prefix + full sequence (arrow after prefix = select-pane)`() {
        val s = state()
        s.arm()
        assertEquals("\u0002\u001B[C", s.transform("\u001B[C"))
    }

    @Test
    fun `armed then paste drops prefix and never drops input (fail-soft, modifier rule)`() {
        val s = state()
        s.arm()
        assertEquals("multi char paste", s.transform("multi char paste"))
        assertFalse("pending ต้องจบ (ห้ามค้างไปครอบ input ภายหลัง)", s.isArmed())
    }

    @Test
    fun `armed then emoji drops prefix (surrogate pair is not a command key)`() {
        val s = state()
        s.arm()
        assertEquals("😀", s.transform("😀"))
        assertFalse(s.isArmed())
    }

    // ---- tap ซ้ำ = literal / quick-key fire / cancel ----

    @Test
    fun `tap while armed sends literal double prefix and disarms (tmux C-b C-b semantics)`() {
        val s = state()
        s.arm()
        assertEquals("\u0002\u0002", s.tapWhileArmed())
        assertFalse(s.isArmed())
    }

    @Test
    fun `beginCommand while armed returns prefix once then null`() {
        val s = state()
        s.arm()
        assertEquals("\u0002", s.beginCommand())
        assertFalse(s.isArmed())
        assertNull("quick-key ตอน not armed (race หลัง timeout) = ห้ามมีอะไรหลุด", s.beginCommand())
    }

    @Test
    fun `disarm clears pending without sending anything`() {
        val s = state()
        s.arm()
        s.disarm() // pane switch / dial ใหม่
        assertFalse(s.isArmed())
        assertEquals("w", s.transform("w"))
    }

    // ---- timeout token ----

    @Test
    fun `timeout of stale generation cannot cancel newer pending`() {
        val s = state()
        val old = s.arm()
        val fresh = s.arm() // user tap ใหม่ = pending รุ่นใหม่
        s.onTimeout(old) // timeout รุ่นเก่ามาช้า
        assertTrue("pending รุ่นใหม่ต้องไม่หลุด", s.isArmed())
        s.onTimeout(fresh)
        assertFalse(s.isArmed())
    }

    // ---- onChange edges ----

    @Test
    fun `onChange fires on armed transitions only`() {
        val s = state()
        var n = 0
        s.onChange = { n++ }
        s.arm() // → armed: fire
        s.transform("x") // → disarm: fire
        s.transform("y") // not armed → not armed: เงียบ
        assertEquals(2, n)
    }
}
