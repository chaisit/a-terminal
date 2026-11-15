package dev.aterminal.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test ของ helper `funBody` ที่ source guards ใช้กำหนดขอบเขต assertion (round-18-env/04) —
 * fixture สังเคราะห์ (ไม่อ่านไฟล์จริง) เพราะสิ่งที่คุมคือ *semantics ของการ cut* ไม่ใช่เนื้อหา source;
 * บทเรียนเดิม: cut แค่ 4-space `private fun`/`internal fun` = method ของ inner class (8-space)
 * กิน body ข้าม sibling ทั้ง bridge → assertTrue เขียวเพราะ sibling / assertFalse แดงผิดตัว
 */
class SourceGuardsTest {

    private val outerOnly = """
        class Outer {
            private fun targetOuter() {
                TOKEN_TARGET_OUTER
            }

            private fun nextOuter() {
                TOKEN_NEXT_OUTER
            }

            internal fun internalOuter() {
                TOKEN_INTERNAL_OUTER
            }

            private fun lastOuter() {
                TOKEN_LAST_OUTER
            }

            fun bareOuter() {
                TOKEN_BARE_OUTER
            }

            override fun overrideOuter() {
                TOKEN_OVERRIDE_OUTER
            }
        }
    """.trimIndent()

    private val bridge = """
        class Outer {
            inner class Bridge {
                private var flag = false

                @JavascriptInterface
                fun onInput() {
                    TOKEN_ON_INPUT
                }

                @JavascriptInterface
                fun onFlush() {
                    TOKEN_ON_FLUSH
                }

                fun plainSibling() {
                    TOKEN_PLAIN_SIBLING
                }
            }
        }
    """.trimIndent()

    @Test fun `body stops before the next top-level private or internal fun`() {
        val priv = funBody(outerOnly, "private fun targetOuter()")
        assertTrue("body ต้องครอบตัว method เอง", priv.contains("TOKEN_TARGET_OUTER"))
        assertFalse("คัตที่ 4-space `private fun` ถัดไป (พฤติกรรมเดิมของ guard ทุกไฟล์)", priv.contains("TOKEN_NEXT_OUTER"))
        val internal = funBody(outerOnly, "internal fun internalOuter()")
        assertTrue(internal.contains("TOKEN_INTERNAL_OUTER"))
        assertFalse("คัตที่ 4-space `internal fun` ถัดไป likewise", internal.contains("TOKEN_LAST_OUTER"))
    }

    @Test fun `body stops before the next top-level bare or override fun sibling`() {
        // hole เดียวกับที่ตั๋ว 04 ปิด แต่ฝั่ง outer: member ไม่มี modifier (เช่น `fun configure(`
        // ใน TerminalView หลัง imeTrace) เคยถูก body กลืนทั้งก้อน — review 11-13 เจอจริง
        val last = funBody(outerOnly, "private fun lastOuter()")
        assertTrue("body ต้องครอบตัว method เอง", last.contains("TOKEN_LAST_OUTER"))
        assertFalse("4-space bare `fun` ถัดไปต้อง cut", last.contains("TOKEN_BARE_OUTER"))
        val bare = funBody(outerOnly, "fun bareOuter()")
        assertTrue(bare.contains("TOKEN_BARE_OUTER"))
        assertFalse("4-space `override fun` ถัดไปก็ต้อง cut เช่นกัน", bare.contains("TOKEN_OVERRIDE_OUTER"))
    }

    @Test fun `body of the last method runs to end of file`() {
        // เคสจริง: guard บางเคส assert สิ่งที่อยู่ท้าย method ก่อน class ปิด — ห้าม cut มั่ว
        val body = funBody(outerOnly, "override fun overrideOuter()")
        assertTrue(body.contains("TOKEN_OVERRIDE_OUTER"))
    }

    @Test fun `missing signature fails with a readable message`() {
        val e = runCatching { funBody(outerOnly, "private fun renamedLongAgo(") }.exceptionOrNull()
        assertTrue("ต้อง fail เป็น assertion (ไม่ใช่ silent empty body)", e is AssertionError)
        assertTrue("message ต้องบอก signature ที่หาไม่เจอ: $e", e!!.message!!.contains("renamedLongAgo"))
    }

    @Test fun `body of an inner-class method stops before the next plain fun sibling`() {
        val body = funBody(bridge, "fun onFlush()")
        assertTrue("body ต้องครอบตัว method เอง", body.contains("TOKEN_ON_FLUSH"))
        assertFalse("sibling 8-space แบบไม่มี annotation ก็ต้อง cut (บน bridge จริงมี plain fun ผสม)", body.contains("TOKEN_PLAIN_SIBLING"))
    }

    @Test fun `body of a method stops before the next inner-class JavascriptInterface sibling`() {
        val body = funBody(bridge, "fun onInput()")
        assertTrue("body ต้องครอบตัว method เอง", body.contains("TOKEN_ON_INPUT"))
        assertFalse("ห้ามกินถึง sibling ที่ตามหลังด้วย @JavascriptInterface (bug เดิมของ onInput)", body.contains("TOKEN_ON_FLUSH"))
        assertFalse("sibling ที่อยู่ถัดจากนั้นไปอีกก็ห้ามหลุดมา", body.contains("TOKEN_PLAIN_SIBLING"))
    }
}
