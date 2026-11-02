package dev.aterminal.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * chord grammar + action table (round 7) — pure JVM, JS ไม่ต้องรู้จัก grammar
 * trace: WT $defs/KeyChordSegment = "[ctrl+][alt+][shift+][win+]<KeyName>"
 */
data class KeyChord(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val key: String)

object ChordParser {
    private val ALIASES = mapOf(
        "esc" to "escape", "return" to "enter",
        "pgup" to "pageup", "pgdn" to "pagedown", "pgdown" to "pagedown",
        "del" to "delete", "ins" to "insert",
    )
    private val NAMED = setOf(
        "escape", "enter", "tab", "space", "backspace", "delete", "insert",
        "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
        "plus", "minus",
    )
    private val FN = Regex("^f([1-9]|1[0-9]|2[0-4])$")

    /** คืน null = ปฏิเสธ binding นี้ (drop + warning ที่ resolver — fail-soft ห้าม crash) */
    fun parse(segment: String): KeyChord? {
        if (segment.isBlank()) return null
        val tokens = segment.split("+").map { it.trim().lowercase() }
        if (tokens.any { it.isEmpty() }) return null // "ctrl+" / "" — modifier รันว่าง
        var ctrl = false; var alt = false; var shift = false
        val keys = mutableListOf<String>()
        for (t in tokens) when (t) {
            "ctrl", "control" -> ctrl = true
            "alt", "option" -> alt = true
            "shift" -> shift = true
            // Android ไม่มีปุ่ม Win → ทิ้ง binding (WT schema มี win แต่เครื่องเราไม่มี)
            "win", "windows", "super", "cmd" -> return null
            else -> keys.add(t)
        }
        if (keys.size != 1) return null // ไม่มี key หรือมากกว่า 1 ("a+b" ไม่ support)
        var k = keys[0]
        if (k == "-") k = "minus"
        k = ALIASES[k] ?: k
        val ok = k in NAMED ||
            (k.length == 1 && k[0].isLetterOrDigit()) ||
            FN.matches(k)
        if (!ok) return null // numpad_*, vk(), sc() → ทิ้ง (Android WebView map ไม่ได้ reliably)
        return KeyChord(ctrl, alt, shift, k)
    }
}

enum class ActionTarget { APP, TERMINAL, UNSUPPORTED }

/**
 * action ของเรา = subset ของ WT $defs/ShortcutActionName (82 names) —
 * TERMINAL = state อยู่ที่ xterm (JS execute ตรง), APP = tabs/panes/settings (Kotlin execute)
 * นอก subset = UNSUPPORTED → toast "ยังไม่รองรับ" (ไม่ crash)
 */
object ActionCatalog {
    // WT: TerminalControl-level — selection/scrollback คือ source of truth ฝั่ง JS
    private val TERMINAL = setOf(
        "scrollUp", "scrollDown", "scrollUpPage", "scrollDownPage", "scrollToTop", "scrollToBottom", // WT: $defs/ShortcutActionName (rowsToScroll arg)
        "selectAll", "copy",            // WT: $defs/CopyAction {singleLine, ...}
        "find", "findMatch",            // WT: $defs/FindMatchAction {direction: next|prev}
        "markMode",                     // WT: keyboard selection, spec #4993 (ctrl+shift+m)
        "sendInput",                    // WT: $defs/SendInputAction {input}
        "clearBuffer",                  // WT: $defs/ClearBufferAction {clear: all|screen|scrollback}
    )
    // WT: TerminalApp-level —เจ้าของ tabs/panes/settings
    private val APP = setOf(
        "newTab", "closeTab", "nextTab", "prevTab", "switchToTab",     // WT: $defs/SwitchToTabAction {index}
        "splitPane", "closePane", "closeOtherPanes", "duplicateTab",   // WT: $defs/SplitPaneAction {split, size, ...NewTerminalArgs}
        "toggleSplitOrientation", "moveFocus", "resizePane",           // WT: $defs/MoveFocusAction {direction}, ResizeDirection
        "adjustFontSize", "resetFontSize",                             // WT: $defs/AdjustFontSizeAction {delta}
        "paste",                                                       // WT: app-layer paste (clipboard เป็นของ Android ไม่ใช่ xterm)
    )

    fun targetOf(name: String): ActionTarget = when {
        name in TERMINAL -> ActionTarget.TERMINAL
        name in APP -> ActionTarget.APP
        else -> ActionTarget.UNSUPPORTED
    }
}

/** validate args ต่อ action — นอก subset ที่ implement แล้ว = UNSUPPORTED (fail-soft แบบ unknown keys) */
object ActionCodec {
    private fun dirIn(cmd: Command, field: String, allowed: Set<String>): Boolean =
        cmd !is Command.Arg || cmd.args[field]?.jsonPrimitive?.contentOrNull == null ||
            cmd.args[field]!!.jsonPrimitive.contentOrNull in allowed

    fun validate(cmd: Command): ActionTarget {
        val name = Command.nameOf(cmd) ?: return ActionTarget.UNSUPPORTED
        val t = ActionCatalog.targetOf(name)
        if (t == ActionTarget.UNSUPPORTED) return t
        return when (name) {
            // WT FocusDirection มี 9 ค่า — รอบนี้ map 5 (previous = สลับ pane ที่ focus ก่อนหน้า)
            "moveFocus" -> if (dirIn(cmd, "direction", setOf("left", "right", "up", "down", "previous"))) t else ActionTarget.UNSUPPORTED
            "resizePane" -> if (dirIn(cmd, "direction", setOf("left", "right", "up", "down"))) t else ActionTarget.UNSUPPORTED
            // mobile 2-pane cap: WT SplitDirection auto|up|right|down|left — up/left ไม่มีที่วางจริง → รับ auto|right|down
            "splitPane" -> if (dirIn(cmd, "split", setOf("auto", "right", "down"))) t else ActionTarget.UNSUPPORTED
            "findMatch" -> if (dirIn(cmd, "direction", setOf("next", "prev"))) t else ActionTarget.UNSUPPORTED
            "adjustFontSize" -> if (cmd !is Command.Arg || cmd.args["delta"]?.jsonPrimitive?.intOrNull != null) t else ActionTarget.UNSUPPORTED
            else -> t
        }
    }
}

data class ResolvedEntry(val chord: KeyChord, val command: Command, val target: ActionTarget)

/** ตารางที่ผ่าน merge+validate แล้ว — toJsJson คือ app↔JS contract (golden test ล็อก format) */
data class ResolvedTable(val entries: List<ResolvedEntry>) {
    fun toJsJson(): String = entries.joinToString(",", "[", "]") { e ->
        val cmd = when (val c = e.command) {
            is Command.Simple -> """{"action":${quote(c.name)}}"""
            is Command.Arg -> buildString {
                append("""{"action":${quote(c.name)}""")
                c.args.forEach { (k, v) -> append(",${quote(k)}:$v") }
                append("}")
            }
            Command.Unknown -> """{"action":"unbound"}"""
        }
        """{"ctrl":${e.chord.ctrl},"alt":${e.chord.alt},"shift":${e.chord.shift},""" +
            """"key":${quote(e.chord.key)},"target":"${e.target.name.lowercase()}","cmd":$cmd}"""
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * merge semantics ตาม WT: defaults ฝังใน → user file layer ทับ (ลำดับไฟล์ = last-wins),
 * "unbound" (id หรือ command) หรือ entry ที่มีแค่ keys = ปิด default; id ไม่รู้จัก = drop + warn
 */
object KeybindingResolver {

    data class Resolved(val table: ResolvedTable, val warnings: List<String>)

    /** mirror chord จริงจาก WT defaults.json (src/cascadia/TerminalSettingsModel/defaults.json) */
    val DEFAULTS: List<Pair<String, Command>> = listOf(
        "ctrl+shift+t" to Command.Simple("newTab"),
        "ctrl+shift+w" to Command.Simple("closePane"),
        "ctrl+tab" to Command.Simple("nextTab"),
        "ctrl+shift+tab" to Command.Simple("prevTab"),
        "alt+left" to argCommand("moveFocus", "direction" to "left"),
        "alt+right" to argCommand("moveFocus", "direction" to "right"),
        "alt+up" to argCommand("moveFocus", "direction" to "up"),
        "alt+down" to argCommand("moveFocus", "direction" to "down"),
        "ctrl+alt+left" to argCommand("moveFocus", "direction" to "previous"),
        "alt+shift+left" to argCommand("resizePane", "direction" to "left"),
        "alt+shift+right" to argCommand("resizePane", "direction" to "right"),
        "alt+shift+up" to argCommand("resizePane", "direction" to "up"),
        "alt+shift+down" to argCommand("resizePane", "direction" to "down"),
        "ctrl+shift+c" to argCommand("copy", "singleLine" to "false"),
        "ctrl+shift+v" to Command.Simple("paste"),
        "ctrl+shift+f" to Command.Simple("find"),
        "ctrl+shift+end" to Command.Simple("scrollToBottom"),
        "ctrl+shift+home" to Command.Simple("scrollToTop"),
        "ctrl+shift+up" to Command.Simple("scrollUp"),
        "ctrl+shift+down" to Command.Simple("scrollDown"),
        "ctrl+shift+pageup" to Command.Simple("scrollUpPage"),
        "ctrl+shift+pagedown" to Command.Simple("scrollDownPage"),
        "ctrl+plus" to argCommand("adjustFontSize", "delta" to "1"),
        "ctrl+minus" to argCommand("adjustFontSize", "delta" to "-1"),
        "ctrl+0" to Command.Simple("resetFontSize"),
        "ctrl+shift+m" to Command.Simple("markMode"),
        "ctrl+shift+a" to Command.Simple("selectAll"),
        "ctrl+shift+k" to argCommand("clearBuffer", "clear" to "all"),
        "ctrl+shift+d" to Command.Simple("duplicateTab"),
        // หมายเหตุ: findMatch ไม่มี default chord ใน WT เช่นกัน (command palette เท่านั้น)
    )

    fun resolve(settings: Settings): Resolved {
        val table = LinkedHashMap<KeyChord, ResolvedEntry>()
        val warnings = mutableListOf<String>()

        for ((chordStr, cmd) in DEFAULTS) {
            val chord = ChordParser.parse(chordStr)
                ?: error("baked default chord has bad grammar: $chordStr")
            table[chord] = ResolvedEntry(chord, cmd, ActionCodec.validate(cmd))
        }

        for (kb in settings.keybindings) {
            if (kb.keys.isEmpty()) { warnings += "keybinding has no keys, skipped"; continue }
            if (kb.keys.size > 1) { // multi-chord sequence (ctrl+k,ctrl+f) — นอกขอบเขตรอบ 7
                warnings += "multi-chord ${kb.keys} is not supported, skipped"; continue
            }
            val chord = ChordParser.parse(kb.keys[0])
            if (chord == null) { warnings += "invalid chord '${kb.keys[0]}', skipped"; continue }
            if (!chord.ctrl && !chord.alt && !chord.shift) {
                warnings += "chord '${kb.keys[0]}' needs a modifier, skipped"; continue
            }
            val legacy = kb.command
            if (legacy == Command.Unknown) { warnings += "invalid command type, skipped"; continue }
            val cmd: Command? = when {
                kb.id != null && kb.id != "unbound" -> settings.actions.find { it.id == kb.id }?.command
                kb.id == "unbound" -> null // ปิด default (WT: "unbound" = disable)
                legacy != null -> if (Command.nameOf(legacy) == "unbound") null else legacy // legacy string หรือ object ตาม WT
                else -> null // WT: keys อย่างเดียว (no id) = disable default
            }
            if (kb.id != null && kb.id != "unbound" && cmd == null) {
                warnings += "keybinding id '${kb.id}' is not in actions, skipped"; continue
            }
            if (cmd == null) { table.remove(chord); continue }
            table[chord] = ResolvedEntry(chord, cmd, ActionCodec.validate(cmd))
        }
        return Resolved(ResolvedTable(table.values.toList()), warnings)
    }
}

/**
 * NativeKeymap — canonical name + chord lookup ฝั่ง native view (round 11 T4)
 * ≡ aterm-keys.js keyName()+lookup(): canonical เดียวกับ ChordParser grammar
 *
 * ตัวเลข keyCode = mirror ของ android.view.KeyEvent.KEYCODE_* — ตรวจด้วย javap จาก
 * android.jar(37.2) จริง (convention ห้ามจำ): A..Z=29..54, NUM=7..16 contiguous,
 * DPAD=19/20/21/22(up/down/left/right), ESCAPE=111, ENTER=66, NUMPAD_ENTER=160, TAB=61,
 * SPACE=62, DEL(backspace)=67, FORWARD_DEL(delete)=112, INSERT=124, HOME=122, MOVE_END=123,
 * PAGE_UP/DOWN=92/93, MINUS=69, EQUALS=70, PLUS=81, NUMPAD_ADD=157, NUMPAD_SUBTRACT=156,
 * F1..F12=131..142, F13..F24=326..337 — :terminal-core เป็น pure JVM จึง import KeyEvent ไม่ได้
 *
 * ≢-choice ที่จด FINDINGS: letter/digit map จาก keyCode ไม่สน shift (JS ได้ e.key='!' จาก shift+2
 * → lookup พัง = bug ฝั่ง JS; native ตรงตาม WT chord grammar มากกว่า — ctrl+shift+2 match จริง)
 */
object NativeKeymap {

    /**
     * round 12 T5 — plain (no ctrl/alt) punctuation → ตัวอักษร จาก keyCode
     * ใช้เฉพาะ fallback เมื่อ `event.unicodeChar == 0` (physical layout ที่ WebView ไม่ได้ give char)
     * — ห้าม map ตัวที่ unicodeChar ให้ได้ (layout จริงต้องชนะ table นี้)
     *
     * keyCode ตรวจด้วย `javap -constants -cp <sdk>/platforms/android-34/android.jar android.view.KeyEvent`
     * (convention ห้ามจำ): COMMA=55 PERIOD=56 SLASH=76 SEMICOLON=74 APOSTROPHE=75 AT=77 NUM=78
     * PLUS=81 MINUS=69 EQUALS=70 LEFT_BRACKET=71 RIGHT_BRACKET=72 BACKSLASH=73 GRAVE=68
     *
     * shift variant = US-ANSIC มาตรฐาน (shift+55 '<' ฯลฯ) — key ที่ shift แล้วเป็น char เดิม
     * (AT/NUM/PLUS/STAR) คืน char เดียวโดยไม่สน shift; คืน null = ไม่มีใน table
     */
    fun plainPunctuation(keyCode: Int, shift: Boolean): String? = when (keyCode) {
        55 -> if (shift) "<" else ","   // COMMA
        56 -> if (shift) ">" else "."   // PERIOD
        76 -> if (shift) "?" else "/"   // SLASH
        74 -> if (shift) ":" else ";"   // SEMICOLON
        75 -> if (shift) "\"" else "'"  // APOSTROPHE
        68 -> if (shift) "~" else "`"   // GRAVE
        69 -> if (shift) "_" else "-"   // MINUS
        70 -> if (shift) "+" else "="   // EQUALS
        71 -> if (shift) "{" else "["   // LEFT_BRACKET
        72 -> if (shift) "}" else "]"   // RIGHT_BRACKET
        73 -> if (shift) "|" else "\\"  // BACKSLASH
        77 -> "@"                        // AT (JIS  dedicated, ไม่มี shift variant)
        78 -> "#"                        // NUM (JIS)
        81 -> "+"                        // PLUS
        17 -> "*"                        // STAR
        else -> null
    }

    /** คืน "" = ไม่มี canonical (modifier press, numpad-comma, media keys ฯลฯ = never match ≡ JS) */
    fun canonicalName(keyCode: Int, shift: Boolean): String = when (keyCode) {
        19 -> "up"; 20 -> "down"; 21 -> "left"; 22 -> "right"
        111 -> "escape"
        66, 160 -> "enter"
        61 -> "tab"
        62 -> "space"
        67 -> "backspace"
        112 -> "delete"
        124 -> "insert"
        122 -> "home"; 123 -> "end"
        92 -> "pageup"; 93 -> "pagedown"
        69 -> "minus" // JS: CODE ก่อน rule → shift+minus ยัง 'minus'
        81, 157 -> "plus"
        156 -> "minus"
        70 -> if (shift) "plus" else "minus" // JS: `k==='equal' → shift?plus:minus`
        in 29..54 -> ('a' + (keyCode - 29)).toString()
        in 7..16 -> ('0' + (keyCode - 7)).toString()
        in 144..153 -> ('0' + (keyCode - 144)).toString() // NUMPAD_0..9 (JS: e.key ของ digit เหมือนกัน)
        in 131..142 -> "f" + (keyCode - 130)
        in 326..337 -> "f" + (keyCode - 313)
        else -> ""
    }

    /** ≡ JS lookup: exact key+ctrl+alt+shift (win ไม่มีบน Android — resolver ทิ้ง binding ไปแล้ว) */
    fun match(table: ResolvedTable, ctrl: Boolean, alt: Boolean, shift: Boolean, key: String): ResolvedEntry? {
        if (key.isEmpty()) return null
        return table.entries.firstOrNull {
            it.chord.key == key && it.chord.ctrl == ctrl && it.chord.alt == alt && it.chord.shift == shift
        }
    }

}

/** helper สำหรับ dispatcher/test: อ่าน string arg จาก Command.Arg */
fun Command.strArg(field: String): String? =
    (this as? Command.Arg)?.args?.get(field)?.jsonPrimitive?.contentOrNull

/** helper: size (0–1) ของ splitPane — null = default ของ layout */
fun Command.doubleArg(field: String): Double? =
    (this as? Command.Arg)?.args?.get(field)?.jsonPrimitive?.content?.toDoubleOrNull()

/** helper: delta/fontSize/index (WT เขียนเป็น number หรือ string ก็ได้ — lenient เหมือน isLenient) */
fun Command.intArg(field: String): Int? =
    (this as? Command.Arg)?.args?.get(field)?.jsonPrimitive?.content?.toIntOrNull()
