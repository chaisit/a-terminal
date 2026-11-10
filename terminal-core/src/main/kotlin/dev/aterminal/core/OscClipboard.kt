package dev.aterminal.core

/**
 * ticket tmux/03: OSC 52 codec (pure-Kotlin) — xterm.js/`:engine` ส่ง payload มาให้, เรา decode/encode ตรงนี้
 *
 * wire ที่วัดจริงบน lab 11-07 (spike transcript ในตั๋ว):
 *  - tmux copy-mode `y` → `\x1b]52;;<b64>\x07` — **target ว่าง** (ไม่ใช่ `c`) ห้ามตกใจ
 *  - pane printf (ต้อง `set-clipboard on` + client มี clipboard feature) → `\x1b]52;c;<b64>\x07`
 *  - query `52;<target>;?`: tmux 3.4/3.6 **ตอบเองจาก paste buffer** ไม่เคยส่งถึง client
 *    (`tty_clipboard_query` ยังไม่มี caller ใน source สองรุ่น) — path query ของเราจึงมีไว้สำหรับ
 *    โปรแกรมที่ยิงตรงถึง terminal โดยไม่มี tmux คั่น (vim `\`+` on bare ssh)
 *  - terminfo `Ms` ของ container ไม่มี (วัด infocmp) แต่ tmux 3.4/3.6 มี default `terminal-features`
 *    = `xterm*:clipboard,...` (options-table.c) → inject Ms ให้ TERM ของเราเอง; preset เพิ่มบรรทัด
 *    เดียวกันแบบ redundant-แต่กันรุ่นเก่า — codec ไม่เกี่ยว gate ใดๆ
 *
 * target = selection-buffer flag string (xterm: c=clipboard p=primary s=secondary; tmux buffers 0-9; q=no-osc)
 * — เก็บ raw ไม่ normalize; ผู้เรียกไม่แยกพฤติกรรมตาม target (Android มี clipboard เดียว)
 */
object OscClipboard {

    /** เพดาน b64 ที่รับ (clipboard bomb — payload ใหญ่ห้ามลาก heap ฝั่ง app; ~750KB decoded) */
    const val MAX_B64_CHARS = 1_000_000

    /** เพดาน reply = ขาเขียนกลับเข้า pty กัน symmetric กัน (≈750KB decoded เท่าขารับ); เกิน = null ผู้เรียก skip */
    const val MAX_REPLY_BYTES = MAX_B64_CHARS * 3 / 4

    // ว่างได้ (วัด tmux copy-mode = `;;`); allow list = xterm c/p/s + q (no-osc) + tmux buffers 0-9 + comma (xterm `c,p`)
    // tmux input_osc_52 (3.6, วัด source) แค่ *ข้าม* อักขระนอก allow list ไม่ reject →
    // semantics ฝั่ง client ต้องเท่ากัน: filter ตัวแปลกออก เก็บ write ไว้ (ห้าม reject ทั้ง payload)
    private const val TARGET_ALLOWED = "cpsq0123456789,"

    /** decode แล้ว (false) หรือ query ต้องการ clipboard เรา (true) — data ว่างเมื่อ query */
    data class Osc52(val target: String, val data: ByteArray, val query: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Osc52 && target == other.target && query == other.query && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * (31 * target.hashCode() + data.contentHashCode()) + query.hashCode()
    }

    /**
     * payload = ส่วนหลัง `52;` ทั้งก้อน (เช่น `c;aGVsbG8=`; wire `52;;?` = `;?`) — ตัวแบ่ง `;` แรกคือ end ของ target
     * convention นี้ตรงกับทั้งสอง caller: xterm.js OscHandler ตัด `52;` ให้ (`_data` เริ่มเก็บหลัง `;` แรก) และ engine onOsc(52, text)
     * เหมือน tmux 3.4 input_osc_52 (strchr(p,';')) ; base64 พัง/ใหญ่เกิน = null (drop เงียบ); อักขระแปลก *ใน* target = ข้ามตัวนั้น ≡ tmux
     * ว่าง-ไม่มี payload = null เช่นกัน (tmux: len==0 → return)
     */
    fun decodePayload(payload: String): Osc52? {
        val semi = payload.indexOf(';')
        if (semi < 0) return null
        val target = payload.substring(0, semi).filter { it in TARGET_ALLOWED }
        val body = payload.substring(semi + 1)
        if (body.isEmpty()) return null
        if (body == "?") return Osc52(target, ByteArray(0), query = true)
        if (body.length > MAX_B64_CHARS) return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(body) }
            .recoverCatching { // client บางตัวส่ง b64 ไม่ใส่ padding — เติมแล้วลองใหม่ (strict decoder ไม่รับ)
                val pad = "=".repeat((4 - body.length % 4) % 4)
                java.util.Base64.getDecoder().decode(body + pad)
            }
            .getOrNull() ?: return null
        return Osc52(target, bytes, query = false)
    }

    /**
     * ตอบ query: ใช้ terminators แบบเดียวกับ `Ms` ที่ tmux ตั้ง (`\x07`) — xterm/tmux ล้วนรับทั้ง ST และ BEL
     * (วัด: tmux 3.4 input_reply_clipboard เลือก terminators ตาม query ที่มา; client ตอบ BEL ได้ทั้งสองทาง)
     */
    fun encodeReply(target: String, data: ByteArray): String? {
        if (data.size > MAX_REPLY_BYTES) return null // clip จัมโบ้ = ไม่ตอบ (reply ใหญ่เข้า pty = ระเบิดฝั่ง transport)
        return "\u001b]52;$target;${java.util.Base64.getEncoder().encodeToString(data)}\u0007"
    }
}
