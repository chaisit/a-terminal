package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NativeKeymap golden (round 11 T4) — canonical names ≡ aterm-keys.js CODE/keyName,
 * lookup ≡ JS table.find; keyCode values ตรวจ javap android.jar แล้ว (ดู header NativeKeymap)
 */
class NativeKeymapTest {

    // ---------- canonicalName ----------
    @Test fun `named keys match JS CODE table`() {
        assertEquals("up", NativeKeymap.canonicalName(19, false))
        assertEquals("down", NativeKeymap.canonicalName(20, false))
        assertEquals("left", NativeKeymap.canonicalName(21, false))
        assertEquals("right", NativeKeymap.canonicalName(22, false))
        assertEquals("escape", NativeKeymap.canonicalName(111, false))
        assertEquals("enter", NativeKeymap.canonicalName(66, false))
        assertEquals("enter", NativeKeymap.canonicalName(160, false)) // NUMPAD_ENTER
        assertEquals("tab", NativeKeymap.canonicalName(61, false))
        assertEquals("space", NativeKeymap.canonicalName(62, false))
        assertEquals("backspace", NativeKeymap.canonicalName(67, false)) // DEL = backspace ฝั่ง Android
        assertEquals("delete", NativeKeymap.canonicalName(112, false))
        assertEquals("insert", NativeKeymap.canonicalName(124, false))
        assertEquals("home", NativeKeymap.canonicalName(122, false))
        assertEquals("end", NativeKeymap.canonicalName(123, false))
        assertEquals("pageup", NativeKeymap.canonicalName(92, false))
        assertEquals("pagedown", NativeKeymap.canonicalName(93, false))
    }

    @Test fun `equal plus minus rules equal js`() {
        assertEquals("minus", NativeKeymap.canonicalName(70, false)) // EQUALS ไม่ shift → minus
        assertEquals("plus", NativeKeymap.canonicalName(70, true)) // shift+equal → plus
        assertEquals("minus", NativeKeymap.canonicalName(69, true)) // MINUS ยัง minus (JS CODE ก่อน)
        assertEquals("plus", NativeKeymap.canonicalName(81, true)) // PLUS
        assertEquals("plus", NativeKeymap.canonicalName(157, false)) // NUMPAD_ADD
        assertEquals("minus", NativeKeymap.canonicalName(156, false)) // NUMPAD_SUBTRACT
    }

    @Test fun `letters digits fn keys contiguous ranges`() {
        assertEquals("a", NativeKeymap.canonicalName(29, false))
        assertEquals("z", NativeKeymap.canonicalName(54, false))
        assertEquals("c", NativeKeymap.canonicalName(31, true)) // shift ไม่เปลี่ยน canonical (JS e.key lowercase)
        assertEquals("0", NativeKeymap.canonicalName(7, false))
        assertEquals("9", NativeKeymap.canonicalName(16, false))
        assertEquals("5", NativeKeymap.canonicalName(149, false)) // NUMPAD_5
        assertEquals("f1", NativeKeymap.canonicalName(131, false))
        assertEquals("f12", NativeKeymap.canonicalName(142, false))
        assertEquals("f13", NativeKeymap.canonicalName(326, false))
        assertEquals("f24", NativeKeymap.canonicalName(337, false))
    }

    @Test fun `modifier presses and unmapped keys get empty name`() {
        for (kc in listOf(59, 60, 113, 114, 57, 58, 1, 4, 85)) { // SHIFT/CTRL/ALT left+right, HOME, BACK, media
            assertEquals("", NativeKeymap.canonicalName(kc, false))
        }
    }

    // ---------- match ผ่าน resolve (defaults + overlay + unbound) ----------
    private val defaults = KeybindingResolver.resolve(Settings()).table

    @Test fun `defaults match through keyCode path`() {
        // ctrl+shift+c (113+59+C=31) → copy
        val c = NativeKeymap.match(defaults, ctrl = true, alt = false, shift = true, key = NativeKeymap.canonicalName(31, true))
        assertEquals("copy", Command.nameOf(c!!.command))
        // ctrl+tab → nextTab (shift ต้อง false — exact match)
        assertEquals("nextTab", Command.nameOf(NativeKeymap.match(defaults, true, false, false, "tab")!!.command))
        assertEquals("prevTab", Command.nameOf(NativeKeymap.match(defaults, true, false, true, "tab")!!.command)) // คนละ entry กับ ctrl+tab
        // alt+shift+left → resizePane
        assertEquals("resizePane", Command.nameOf(NativeKeymap.match(defaults, false, true, true, "left")!!.command))
        // ctrl+plus: entry default shift=false — ถึงได้ผ่าน numpad+ (157, no shift) ≡ JS;
        // ctrl+shift+= (EQUALS+shift → 'plus' แต่ shift=true) ไม่ match default = quirk เดิมของ JS (จด FINDINGS)
        assertEquals("adjustFontSize", Command.nameOf(NativeKeymap.match(defaults, true, false, false, NativeKeymap.canonicalName(157, false))!!.command))
        assertNull(NativeKeymap.match(defaults, true, false, true, NativeKeymap.canonicalName(70, true)))
        assertEquals("resetFontSize", Command.nameOf(NativeKeymap.match(defaults, true, false, false, NativeKeymap.canonicalName(7, false))!!.command))
        assertEquals("scrollUpPage", Command.nameOf(NativeKeymap.match(defaults, true, false, true, "pageup")!!.command))
        assertEquals("markMode", Command.nameOf(NativeKeymap.match(defaults, true, false, true, "m")!!.command))
    }

    @Test fun `override and unbound visible through match`() {
        val r = KeybindingResolver.resolve(
            SettingsLoader.parse(
                """{"keybindings":[
                   {"keys":"ctrl+shift+c","command":"newTab"},
                   {"keys":"ctrl+tab","id":"unbound"}]}"""
            )
        )
        assertEquals("newTab", Command.nameOf(NativeKeymap.match(r.table, true, false, true, "c")!!.command))
        assertNull(NativeKeymap.match(r.table, true, false, false, "tab"))
    }

    @Test fun `empty name never matches and plain letters without modifier never match`() {
        assertNull(NativeKeymap.match(defaults, false, false, false, ""))
        assertNull(NativeKeymap.match(defaults, false, false, false, "c")) // grammar บังคับ modifier — default ปลอดภัย
    }

    // ---------- plainPunctuation (round 12 T5) ----------
    // keyCode ทุกตัว = ค่าจริงจาก `javap -constants android.view.KeyEvent` (android-34)
    @Test fun `punctuation table matches javap keycodes`() {
        val golden = mapOf(
            55 to ("," to "<"), // COMMA
            56 to ("." to ">"), // PERIOD
            76 to ("/" to "?"), // SLASH
            74 to (";" to ":"), // SEMICOLON
            75 to ("'" to "\""), // APOSTROPHE
            68 to ("`" to "~"), // GRAVE
            69 to ("-" to "_"), // MINUS
            70 to ("=" to "+"), // EQUALS
            71 to ("[" to "{"), // LEFT_BRACKET
            72 to ("]" to "}"), // RIGHT_BRACKET
            73 to ("\\" to "|"), // BACKSLASH
            77 to ("@" to "@"), // AT
            78 to ("#" to "#"), // NUM
            81 to ("+" to "+"), // PLUS
            17 to ("*" to "*"), // STAR
        )
        for ((code, pair) in golden) {
            val (plain, shifted) = pair
            assertEquals("keyCode $code plain", plain, NativeKeymap.plainPunctuation(code, false))
            assertEquals("keyCode $code shift", shifted, NativeKeymap.plainPunctuation(code, true))
        }
    }

    @Test fun `punctuation table is silent on named and letter keycodes`() {
        // ตัวที่มี canonical/text path อยู่แล้ว ต้องคืน null — ไม่งั้น fallback ใหม่จะกินของเดิม
        for (code in intArrayOf(61, 62, 66, 67, 111, 112, 122, 123, 92, 93, 19, 20, 21, 22, 29, 41, 7, 16, 131)) {
            assertNull("keyCode $code must stay out of punctuation table", NativeKeymap.plainPunctuation(code, false))
            assertNull("keyCode $code shift must stay out", NativeKeymap.plainPunctuation(code, true))
        }
    }
}
