package dev.aterminal.core

/**
 * ticket tmux/02: ผลจาก `tmux -V` → version → capability table (pure-Kotlin, gate ด้วย JVM ได้)
 *
 * design notes:
 *  - fail-soft ทุกชั้น: output ของ remote command = untrusted — parse ไม่ match = null = features เงียบทั้งหมด
 *  - gate ทุกตัวอ้าง research §1.12 (CHANGELOG/primary source) — ไม่ใช่ ticket prose
 *  - เก็บ per-connection เท่านั้น (ห้าม persist — เครื่องเดียวกันเปลี่ยน tmux ได้, และ pin ผิด version = feature พังเงียบ)
 */
data class TmuxVersion(
    val major: Int,
    val minor: Int,
    /** release patch letter (`tmux 3.2a` = 'a'); null = ไม่มี letter ซึ่ง order ต่ำกว่าทุก letter (3.2 < 3.2a) */
    val patch: Char? = null,
) : Comparable<TmuxVersion> {

    /** patch letter เทียบแบบตัวอักษร (tmux ปล่อย a→b ต่อลำดับเท่านั้น — 3.6a คือ letter เดียวที่มี) */
    override fun compareTo(other: TmuxVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        val a = patch?.code ?: 'a'.code - 1 // no-letter ต่ำกว่า 'a'
        val b = other.patch?.code ?: 'a'.code - 1
        return a.compareTo(b)
    }

    /** gate helper — เขียน `version.atLeast(3, 2, 'a')` ริม ๆ capability หนึ่งบรรทัดต่อ gate */
    fun atLeast(major: Int, minor: Int, patch: Char? = null): Boolean =
        this >= TmuxVersion(major, minor, patch)

    companion object {
        // รูปแบบที่ tmux -V พิมพ์จริง: "tmux 3.6" | "tmux 3.6a" | git build = "tmux next-3.7"
        // (research §1.12: next-X = build ก่อน release X มีฟีเจอร์ของ X รวมมาแล้ว → นับเป็น X)
        private val FORMAT = Regex("^tmux (?:next-)?(\\d+)\\.(\\d+)([a-z])?$")

        /**
         * ผลรวมของ stdout+stderr จาก exec `tmux -V`; null = ไม่มี tmux / output แปลก = fail-soft
         * scan ทุก line (stderr รวมมาด้วย — wrapper/shim ของ managed host พิมพ์ นำหน้าได้ ห้าม commit กับ line แรก)
         */
        fun parse(probeOutput: String?): TmuxVersion? =
            probeOutput?.lineSequence()?.firstNotNullOfOrNull { line ->
                FORMAT.matchEntire(line.trim())?.let { m ->
                    TmuxVersion(
                        major = m.groupValues[1].toInt(),
                        minor = m.groupValues[2].toInt(),
                        patch = m.groupValues[3].firstOrNull(),
                    )
                }
            }
    }
}

/**
 * ตาราง capability จาก version เดียว — feature gate ทั้งหมดถาม object นี้
 * (central API: ผู้เรียกถาม `caps?.layoutJson` ไม่เทียบ version เอง — gate เลื่อนที่เดียว)
 *
 * ราย gate อ้าง research §1.12 (tmux CHANGELOG):
 *  2.6 set-clipboard 3-state · 3.0 send-keys -H · 3.2 display-popup/%pause/pause-after/%extended-output/refresh-client -B
 *  3.2a extended-keys "always" + client-detached notification · 3.4 %config-error · 3.5 extended-keys-format
 *  3.6 allow-set-title + OSC 4/10/11 forwarding + refresh-client -r · 3.7 floating panes + layout JSON (3.8 ขยายต่อ)
 *  3.8 theme/new-layouts (ยังไม่ release ตอน sprint นี้ — gate เผื่อ)
 */
class TmuxCapabilities(val version: TmuxVersion) {

    /** `set-clipboard` 3-state + OSC52 ฝั่ง client — §1.12 = 2.6 (ticket prose เขียน 3.2 → ยึด §1.12) */
    val osc52Clipboard get() = version.atLeast(2, 6)

    /** `send-keys -H` (hex byte send — เลี่ยง key-table AmbiguousKey ของ ctrl/alt combos) */
    val sendKeysHex get() = version.atLeast(3, 0)

    /** `%pause` / `pause-after` (flow control ฝั่ง control-mode) */
    val pauseAfter get() = version.atLeast(3, 2)

    /** `%extended-output` (output event แบบมี length prefix) */
    val extendedOutput get() = version.atLeast(3, 2)

    /** `refresh-client -B` subscriptions / `%subscription-changed` */
    val subscriptions get() = version.atLeast(3, 2)

    /** notification เมื่อ client อื่น detach (มาพร้อม patch 3.2a — patch-level gate gate แรกของตาราง) */
    val clientDetachedNotify get() = version.atLeast(3, 2, 'a')

    /** `%config-error` (อ่าน error จาก tmux.conf ที่ parse fail) */
    val configErrorNotify get() = version.atLeast(3, 4)

    /** `extended-keys-format` option */
    val extendedKeysFormat get() = version.atLeast(3, 5)

    /** allow-set-title + forward OSC 4/10/11 + `refresh-client -r` */
    val oscForwarding get() = version.atLeast(3, 6)

    /** control-mode layout = JSON string (3.7; 3.8 เพิ่ม field แต่ flag/format เดิมใช้ได้) */
    val layoutJson get() = version.atLeast(3, 7)

    /** theme options + new layouts — 3.8 (ยังไม่ release: เปิดเฉพาะ git build `next-3.8`) */
    val themeOptions get() = version.atLeast(3, 8)
}
