package dev.aterminal.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * keybindings/actions สองเลเยอร์ตาม WT จริง (round 7)
 * trace: refs/terminal/doc/cascadia/profiles.schema.json
 *  - $defs/Keybinding  → {keys: KeyChordSegment | [segments], id} — modern shape
 *  - $defs/FullCommand → actions[]: {command, id, name} — command เป็น string ("find")
 *    หรือ object ($defs/ShortcutAction: "action" อยู่ระดับเดียวกับ args, flatten — ไม่มี nested args map)
 *  - legacy {keys, command} (round ≤6 ของเรา + WT เก่า) ยัง parse ได้ — Command.Simple
 * ทุกอย่างที่ parse ไม่ออก = Unknown/ignore (fail-soft เหมือน unknown keys) ห้าม crash
 */
@Serializable(with = CommandSerializer::class)
sealed class Command {
    /** WT: `"command": "newTab"` (bare ShortcutActionName) */
    data class Simple(val name: String) : Command()

    /** WT: `{"action": "findMatch", "direction": "next"}` — args ที่เราไม่รู้จักค้างอยู่ใน args เฉย ๆ */
    data class Arg(val name: String, val args: JsonObject) : Command()

    /** type ผิดรูปในไฟล์ (number, nested...) → fail-soft, drop warning ที่ resolver */
    object Unknown : Command()

    companion object {
        fun nameOf(c: Command): String? = when (c) {
            is Simple -> c.name
            is Arg -> c.name
            Unknown -> null
        }
    }
}

/** $defs/FullCommand-lite — เก็บ id (ผูก keybinding↔command), name เก็บไว้ display (ยังไม่ใช้) */
@Serializable
data class ActionEntry(
    val id: String,
    val command: Command,
    // WT จริง name เป็น string หรือ {"key":..,"value":..} (resource ref) — lenient: ไม่ใช่ string = null
    @Serializable(with = LenientNameSerializer::class)
    val name: String? = null,
)

object LenientNameSerializer : KSerializer<String?> {
    override val descriptor = buildClassSerialDescriptor("LenientName")
    override fun deserialize(decoder: Decoder): String? =
        when (val el = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> el.contentOrNull
            else -> null // {"key":..,"value":..} resource form — รับได้, ไม่ display รอบนี้
        }

    override fun serialize(encoder: Encoder, value: String?) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(if (value == null) JsonNull else JsonPrimitive(value))
    }
}

/** รับสอง spelling ของ command ตาม schema; render คืนรูปเดิม (string→string, object→object) */
object CommandSerializer : KSerializer<Command> {
    override val descriptor = buildClassSerialDescriptor("Command")

    override fun deserialize(decoder: Decoder): Command {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when {
            el is JsonPrimitive && el.isString -> Command.Simple(el.content)
            el is JsonObject -> {
                val action = (el["action"] ?: el["command"])?.jsonPrimitive?.contentOrNull
                    ?: return Command.Unknown
                Command.Arg(action, JsonObject(el.filterKeys { it != "action" && it != "command" }))
            }
            else -> Command.Unknown
        }
    }

    override fun serialize(encoder: Encoder, value: Command) {
        val json = encoder as JsonEncoder
        val el: JsonElement = when (value) {
            is Command.Simple -> JsonPrimitive(value.name)
            is Command.Arg -> buildJsonObject {
                put("action", value.name)
                value.args.forEach { (k, v) -> put(k, v) }
            }
            Command.Unknown -> JsonPrimitive("unbound") // "unbound" เป็น action name จริงของ WT = disable
        }
        json.encodeJsonElement(el)
    }
}

/**
 * keys: WT $defs/Keybinding.keys = "ctrl+shift+f" | ["ctrl+k","ctrl+f"]
 * เก็บเป็น List<String> เสมอ; render กลับเป็น string เมื่อมี chord เดียว —
 * กันไฟล์ user (round ≤6) ถูก churn เป็น array ตอน persistSettings เขียนทับ
 */
object KeyChordsSerializer : KSerializer<List<String>> {
    override val descriptor = buildClassSerialDescriptor("KeyChords")

    override fun deserialize(decoder: Decoder): List<String> =
        when (val el = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> listOfNotNull(el.contentOrNull)
            is JsonArray -> el.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }

    override fun serialize(encoder: Encoder, value: List<String>) {
        val json = encoder as JsonEncoder
        val el: JsonElement =
            if (value.size == 1) JsonPrimitive(value[0]) else JsonArray(value.map { JsonPrimitive(it) })
        json.encodeJsonElement(el)
    }
}

/** helper สร้าง Arg command แบบอ่านง่ายใน DEFAULTS/tests */
fun argCommand(name: String, vararg args: Pair<String, String>): Command.Arg {
    val rest: Map<String, JsonElement> = args.associate { (k, v) -> k to JsonPrimitive(v) as JsonElement }
    return Command.Arg(name, JsonObject(rest))
}

/** สำหรับ dispatcher: JS ส่ง cmdJson (shape เดียวกับ cmd ใน toJsJson) กลับมา → parse เป็น Command */
object CommandCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseCommandJson(text: String): Command =
        runCatching { json.decodeFromString(CommandSerializer, text) }.getOrDefault(Command.Unknown)

    fun renderCommand(c: Command): String =
        runCatching { json.encodeToString(CommandSerializer, c) }.getOrDefault("\"unbound\"")
}
