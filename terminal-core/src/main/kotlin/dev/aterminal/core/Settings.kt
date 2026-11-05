package dev.aterminal.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings model — subset ของ Windows Terminal settings.json
 * trace กลับ schema จริง: refs/terminal/doc/cascadia/profiles.schema.json ($defs.Profile / Globals / SchemePair)
 * หลัก forward-compat เหมือน WT: JSON ignore unknown keys → _upgrade_ user file ไปได้เรื่อย ๆ
 */
@Serializable
data class Settings(
    val defaultProfile: String? = null,
    val profiles: ProfilesObject = ProfilesObject(),
    val schemes: List<Scheme> = emptyList(),
    val keybindings: List<Keybinding> = emptyList(),
    val actions: List<ActionEntry> = emptyList(), // WT root "actions" (ActionDirectory) — layer ของ id→command
    val copyOnSelect: Boolean = false,
    val trimPaste: Boolean = true,
    val wordDelimiters: String = " /\\()\"'-.,:;<>~!@#$%^&*|+=[]{}~?│",
    // non-WT extension (WT ไม่มี terminal renderer choice ใน schema): "webview" | "native" — ค่าเดิม webview
    // (native = :engine + :engine-render round 9; WT readers ignore field นี้)
    val engine: String = "webview",
    // non-WT extension (round 10 R6, user เลือก "toggle ใน settings"): clip เปลี่ยนจาก app อื่น = auto-paste เข้า pane
    // ค่าเดิม true = พฤติกรรมเดิมทุกตัวอักษร; false = ปิด auto-paste (pasteNow มือยังใช้ได้ปกติ)
    val autoPasteClipboard: Boolean = false,
    // non-WT extension: Windows Terminal ไม่มีแนวคิดนี้ เป็นเรื่องของ Android เท่านั้น
    // true = ถือ partial wake lock ตลอดที่มี session กัน CPU หลับจน server ตัด แลกกับแบตเตอรี่
    val keepCpuAwake: Boolean = false,
    // non-WT extension (round 11 T5): native preedit hold (≡ xterm composition — G8) vs path เดิม feed-ทันที
    // ค่าเดิม true = preedit thật; false = emergency rollback กลับ native IME path เก่า byte-for-byte (เฉพาะ engine=native)
    val preeditEnabled: Boolean = true,
    // non-WT extension (HW 11-05): คำสั่งของ app ต่อ IME เรื่อง "เดาคำให้" — ค่าที่เป็นไปได้ + ความหมาย
    // อยู่ที่ ImeSuggestions (none / strict / strictOnHardware); ค่าไม่รู้จัก = พฤติกรรมที่ shipped มาตลอด
    // default = strict (user ตัดสิน 11-05 หลังวัดบน Tab S10+ + Honor): ขอ flag แรงสุดเสมอ = IME ที่เชื่อ
    // ปิด strip ให้เองตั้งแต่เปิด app; เครื่องที่ IME ไม่เชื่อ = ImeAutocomplete พาไปปิดใน IME เองหนึ่งครั้ง
    // และ IME ที่เชื่อจะไม่ compose = พิมพ์ไทยไม่ได้บนจอสัมผัส → ⋮ ⌨️ สลับกลับ strictOnHardware/none ได้
    val imeSuggestions: String = ImeSuggestions.STRICT,
    // non-WT extension (round 13 T4, user อนุมัติ toggle): เปิด tab + auto-reconnect ตามโครงเดิมหลัง process death
    // WT schema ไม่มี field restore ตรงตัว (startupActions:2543 = action grammar, ไม่ใช่ session state) → extension by precedent
    // ค่าเดิม true; false = boot เดิม (tab ว่างตัวเดียว) ทุกอย่างเหมือน round ≤12
    val restoreSessions: Boolean = true,
    // non-WT extension (round 23 chrome): Keys Bar preset name — WT schema มี keybindings (hardware chords)
    // แต่ไม่มี on-screen key bar เลย → preset reference, layout จริงอยู่ที่ KeyPresets (unknown name → default)
    val keysPreset: String = "default",
    // non-WT extension (round 23 chrome, ADR 0002): ปุ่ม Back บน terminal surface (ไม่มี dialog/IME เปิดค้าง) = ESC
    // ≡ Termux back-key=escape — WT schema ไม่มี Back policy (ไม่มี concept ปุ่ม back ของ Android)
    // ค่าเดิม true = deliberate behavior change ตาม ADR 0002 (swipe ที่เคยเสียเปล่ากลายเป็น ESC ที่มีประโยชน์); false = พฤติกรรมเดิม
    val backAsEscape: Boolean = true,
    // non-WT extension (round 23 chrome, ADR 0002): hardware keyboard เชื่อมต่อ/เปิด = ยุบ Keys Bar คืนพื้นที่ grid
    // WT ไม่มี concept นี้ (คีย์บอร์ดจริงคือ default ของ desktop) — ค่าเดิม false = bar แสดงเสมอเหมือนเดิมทุกตัวอักษร
    val hideKeysBarOnHardwareKeyboard: Boolean = false,
    // non-WT extension (round 23 amendment, GLOSSARY §Distribution): Update Check opt-out —
    // ค่าเดิม true = auto ≤1×/วัน; manual จาก ⋮ ไม่ถูกบล็อกโดย field นี้ (UpdateCheck.shouldAutoCheck)
    val updateChecks: Boolean = true,
    // release ที่ user เคยเห็น/กด Later (Update Notice = ครั้งเดียวต่อ release — "Later" ใน GLOSSARY)
    // ห้ามเทียบกับ semantic version ที่นี่ — เทียบ tag ตรงตัว, release ใหม่กว่า = tag ต่าง = เตือนได้
    val dismissedUpdateTag: String? = null,
    // epoch-ms ของการเช็คครั้งล่าสุด (auto throttle 1×/วัน) — machine state ที่ persist ตามธรรมเนียม
    // lastSettingsHash-free: เขียนผ่าน persistSettings funnel เดียว, watcher กัน self-write อยู่แล้ว
    val lastUpdateCheckAt: Long? = null,
)

@Serializable
data class ProfilesObject(
    val defaults: Profile = Profile(),
    val list: List<Profile> = emptyList(),
)

/**
 * Profile — MVP subset ของ $defs.Profile (63 fields; เราเริ่ม 17)
 * ฟิลด์ที่ไม่ support ยัง "รับได้" (ignored) — ผู้ใช้ copy settings.json จาก WT แล้วไม่พัง
 */
@Serializable
data class Profile(
    val guid: String? = null,
    val name: String? = null,
    val hidden: Boolean = false,
    // --- backend (แทน WT commandline/source/connectionType: ของเรา = ssh|termux) ---
    val type: String = "ssh",                 // "ssh" | "termux"
    val commandline: String? = null,           // WT: profile.commandline
    val startingDirectory: String? = null,     // WT: profile.startingDirectory
    val environment: Map<String, String> = emptyMap(), // WT: profile.environment
    val host: String? = null,                  // ssh only
    val port: Int = 22,
    val user: String? = null,
    // non-WT extension (WT ไม่เก็บ credential ใน json เช่นกัน): อ่านได้, WT readers จะ ignore
    val authMethod: String = "none",           // "password" | "key" | "none" — ค่าเดิม "password" ทำให้ echo/termux ที่ไม่ระบุ field โดนถามรหัส (bug M2.7 เจอบน device)
    val keyAlias: String? = null,              // ชื่อไฟล์ private key ใต้ filesDir/keys/ (ห้ามเป็น path เด็ดขาด)
    // --- appearance (ยก $defs.AppearanceConfig มา subset) ---
    val colorScheme: String? = null,
    // WT: profile.fontFace (free-form string ตาม schema) — ค่าเดิมไม่มี font ติดตั้งจริงบน Android = ตกไป system mono เงียบ
    // round 15-HW: 'FiraCode Nerd Font' ตัวแรกของ stack = bundled asset (app assets/fonts/terminal.ttf, ≡ @font-face ใน terminal.html)
    val fontFace: String = "FiraCode Nerd Font, Cascadia Mono, Noto Sans Thai, monospace",
    val fontSize: Int = 12,
    val historySize: Int = 9001,
    val snapOnInput: Boolean = true,
    val closeOnExit: String = "automatic",     // WT enum: automatic|graceful|never
    val opacity: Int = 100,
    val padding: String = "8,8,8,8",
    val tabTitle: String? = null,
    val icon: String? = null,
)

@Serializable
data class Scheme(
    val name: String,
    // WT SchemePair = {dark:{...}, light:{...}} — MVP: scheme เดียว (colors ตรง ๆ), pair เฟสหลัง
    val foreground: String = "#CCCCCC",
    val background: String = "#0C0C0C",
    val selectionBackground: String? = null,
    val cursorColor: String? = null,
    val black: String = "#0C0C0C", val red: String = "#C50F1F",
    val green: String = "#13A10E", val yellow: String = "#C19C00",
    val blue: String = "#0037DA", val purple: String = "#881798",
    val cyan: String = "#3A96DD", val white: String = "#CCCCCC",
    val brightBlack: String = "#767676", val brightRed: String = "#E74856",
    val brightGreen: String = "#16C60C", val brightYellow: String = "#F9F1A5",
    val brightBlue: String = "#3B78FF", val brightPurple: String = "#B4009E",
    val brightCyan: String = "#61D6D6", val brightWhite: String = "#F2F2F2",
)

/**
 * WT $defs/Keybinding (round 7): keys = chord string หรือ array ของ chords, id ชี้เข้า actions[]
 * legacy {keys, command} (ไฟล์ round ≤6 + WT เก่า) ยัง parse ได้ — resolver จะเห็น command ก่อน id
 * "unbound" (WT ShortcutActionName ตัวหนึ่ง) = ปิด default chord
 */
@Serializable
data class Keybinding(
    @Serializable(with = KeyChordsSerializer::class)
    val keys: List<String> = emptyList(), // รับ "ctrl+shift+f" | ["ctrl+k","ctrl+f"] (KeyChordSegment)
    @Serializable(with = CommandSerializer::class)
    val command: Command? = null,         // legacy inline — WT เก่าใช้ได้ทั้ง "find" และ {"action":...}; ไฟล์ round ≤6 = string
    val id: String? = null,               // modern: อ้าง ActionEntry.id
)

object SettingsLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(text: String): Settings = json.decodeFromString(Settings.serializer(), text)
    fun render(settings: Settings): String =
        json.encodeToString(Settings.serializer(), settings)
}
