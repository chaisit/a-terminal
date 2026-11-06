package dev.aterminal.app

/**
 * ticket tmux/04: state trailing-debounce ของ title ที่ terminal ส่งมา (OSC 0/2)
 *
 * timing เป็นของผู้เรียก (offer = ตั้ง pending + arm() ถ้ายังไม่ได้ schedule; flush+disarm บน main)
 * — gate เป็น pure state จึง test ด้วย JVM ได้
 *
 * semantics (user ตัดสิน 11-05):
 *  - ค่าล่าสุดชนะเสมอ: แม้ค่าล่าสุด == ค่าที่แสดงอยู่ (terminal set/restore title ใน window เดียวกัน
 *    = ต้อง *ยกเลิก* pending ตัวเก่า ไม่ใช่ปล่อยให้ stale ชนะตอน flush)
 *  - ไม่มี clear-back: title คงจนกว่า OSC ค่าใหม่จะมา (≡ desktop terminal) — dial ใหม่ก็ต่อจากค่าเดิม
 *  - input = untrusted (MITM sshd ส่ง OSC ได้): sanitize + cap ความยาวก่อนแตะ UI
 *
 * ทุก method synchronized: offer วิ่งบน bridge thread (WebView JavaBridge) / flush+disarm บน main —
 * flag `scheduled` ต้องอยู่ lock เดียวกัน ไม่งั้น bridge เห็นค่าค้าง (main ล้างแล้ว) → pending ไม่ถูก flush ตลอด
 */
class TerminalTitleGate {

    @Volatile var title: String? = null
        private set
    private var pending: String? = null
    private var scheduled = false

    /** true = pending เปลี่ยนเป็นค่าใหม่ที่ *ต่างจากที่แสดง* → ผู้เรียกจัด flush trailing (ถาม arm() ก่อน) */
    @Synchronized
    fun offer(raw: String): Boolean {
        val clean = sanitize(raw) ?: return false
        if (clean == pending) return false
        if (clean == title) { pending = clean; return false } // latest == กำลังแสดง: neutralize pending เก่า, ไม่มีอะไรต้อง emit
        pending = clean
        return true
    }

    /** true = ผู้เรียกตัวนี้ถือ schedule (เรียก postDelayed ครั้งเดียวต่อ window); คู่ของมันคือ disarm() */
    @Synchronized
    fun arm(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    /** ปล่อย schedule — **เรียกก่อน flush เสมอ** (กัน offer ที่คาบเกี่ยวเห็น flag ค้างแล้วไม่ schedule ใหม่ → pending ติดค้าง) */
    @Synchronized
    fun disarm() {
        scheduled = false
    }

    /** true = มีค่าถูก emit (ผู้เรียก refresh chrome) */
    @Synchronized
    fun flush(): Boolean {
        val p = pending ?: return false
        pending = null
        if (p == title) return false
        title = p
        return true
    }

    companion object {
        const val MAX_LEN = 120

        /** null = ไม่มีเนื้อหาใช้ได้ (blank/ควบคุมล้วน) — pill จะเหลือค่าเดิม/fallback ของมัน */
        fun sanitize(raw: String): String? {
            val sb = StringBuilder(raw.length.coerceAtMost(MAX_LEN + 16))
            var lastSpace = true // กัน leading space
            for (c in raw) {
                if (isInvisibleOrBidi(c)) { // C0/DEL/C1 + bidi & format controls = separator (กัน reordering attack บน pill)
                    if (!lastSpace) { sb.append(' '); lastSpace = true }
                    continue
                }
                if (c == ' ') { if (!lastSpace) { sb.append(' '); lastSpace = true }; continue }
                sb.append(c); lastSpace = false
                if (sb.length >= MAX_LEN) break
            }
            var out = sb.toString().trim()
            // cap นับ UTF-16 unit — ถ้า cut ลงกลาง surrogate pair เก็บ lone high surrogate ทิ้ง (tofu in pill)
            if (out.isNotEmpty() && Character.isHighSurrogate(out[out.length - 1])) out = out.dropLast(1)
            return out.ifBlank { null }?.take(MAX_LEN)
        }

        private fun isInvisibleOrBidi(c: Char): Boolean =
            c.code < 0x20 || c == '\u007F' ||
                c.code in 0x80..0x9F || // C1 (รวม U+0085 NEL)
                c == '\u200E' || c == '\u200F' || // LRM/RLM
                c.code in 0x202A..0x202E || // LRE/RLE/PDF (bidi embedding)
                c.code in 0x2066..0x2069 || // isolates (first-strong direction spoof)
                c == '\uFEFF' // BOM/ZWNBSP
    }
}
