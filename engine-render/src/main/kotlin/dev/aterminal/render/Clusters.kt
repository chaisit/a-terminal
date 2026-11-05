package dev.aterminal.render

import dev.aterminal.engine.term.Unicode

/**
 * Clusters — แตก string เป็น cluster ตามกติกา **cell ของ engine** (round 11 T5) สำหรับ
 * shaping fallback API<31 (TextShaper มี API31+; path เดิม drawText ทั้ง run = Thai marks offset เพี้ยน)
 *
 * -plan เดิมเขียน "BreakIterator" — เปลี่ยนโดยจงใจ (จด FINDINGS): BreakIterator (legacy, ไม่มี
 * GB9c/SpacingMark) *รวม* Mc marks เช่น เดววนาคरी ा เข้า cluster ก่อนหน้า แต่ engine (xterm UnicodeV6)
 * split cell ด้วย width==0 → cluster ≠ cell จริง = grid drift; ที่นี่ head = `Unicode.width(cp) != 0`
 * (หรือ current cluster ยัง zero-width ล้วน) = rule เดียวกับ engine ต่อ codepoint → 1 cell = 1 cluster by construction
 *
 * invariant ฝั่ง run (RunMerger): dummy (w0 cell) ไม่เข้า text, wide เป็น run ของตัวเอง →
 * วาด cluster ที่ `x + i*cellW` ไม่ต้อง measure advance (invariant ทำให้ getTextRunAdvances เกินจำเป็น — จด FINDINGS)
 * เศษ standalone mark (leading zero-width) = cluster ของตัวเอง ≡ engine ที่มี cell ของมันเอง
 */
object Clusters {

    /** engine-cell clusters ตามลำดับ ("" → empty list) */
    fun split(s: String): List<String> {
        val out = ArrayList<String>()
        forEach(s) { a, b -> out.add(s.substring(a, b)) }
        return out
    }

    /**
     * walk เดียวกับ [split] แต่ส่ง (start, end) ของแต่ละ cluster ให้ callback — **ไม่ alloc list
     * และไม่ alloc substring ต่อ cluster** (วาดทั้งจอ = ~1 cluster ต่อ cell ต่อเฟรม — allocation
     * ชุดนี้คือ GC churn ที่วัดได้จากอาการกระตุกของ native renderer)
     * inline = lambda ที่ call site ถูก inline = zero allocation จริง
     */
    inline fun forEach(s: CharSequence, onCluster: (start: Int, end: Int) -> Unit) {
        var start = 0
        var headSeen = false // cluster ปัจจุบันมี printable head หรือยัง (zero-width ล้วน =รับ non-starter ตัวถัดไปได้)
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val w = Unicode.width(cp)
            if (w != 0 || !headSeen) {
                if (i > start) onCluster(start, i)
                start = i
                if (w != 0) headSeen = true
            }
            i += Character.charCount(cp)
        }
        if (start < s.length) onCluster(start, s.length)
    }
}
