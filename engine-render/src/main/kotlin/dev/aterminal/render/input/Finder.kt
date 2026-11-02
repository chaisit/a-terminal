package dev.aterminal.render.input

/**
 * Finder — semantics ≡ `aterm-find.js` (round 7) แปลงตรง: scan ทั้ง buffer ต่อ keystroke,
 * matches = [{row abs, col, len}] (indexOf ไม่ overlap, advance = len ที่ lower/เดิม),
 * resetIdx = "ตัวแรกถัดจาก viewportTop" (JS: findIndex(m.row >= viewportY), ไม่เจอ → 0),
 * next/prev = modulo wrap ≡ show() ของ JS (idx<0 → next=0, prev=n-1)
 *
 * col/len = **index ใน line string** (≢-เล็กน้อยที่จงใจคง ≡ JS: xterm ก็ส่ง string offset ให้ select
 * เป็น cell — row ที่มี dummy/wide จะ offset เพี้ยนตรงกันในสองฝั่ง; view mapping cell → FINDINGS)
 * line provider = trimRight แล้ว (≡ translateToString(true); ผู้เรียกใช้ BufferText.lineText)
 */
class Finder {

    data class Match(val row: Int, val col: Int, val len: Int)

    var query: String = ""
        private set
    var caseSensitive: Boolean = false
    var matches: List<Match> = emptyList()
        private set
    var idx: Int = -1
        private set

    /** ≡ recompute(resetIdx): scan ใหม่ทั้งก้อน, idx = -1 เสมอตอนต้น; resetIdx+มี match → ตัวแรก >= viewportTop
     *  คืน match ที่ควรแสดง (≡ JS show() ที่ recompute เรียกข้างใน) หรือ null = report อย่างเดียว (show0) */
    fun recompute(lineAt: (Int) -> String?, lineCount: Int, resetIdx: Boolean, viewportTop: Int): Match? {
        matches = emptyList()
        idx = -1
        if (query.isNotEmpty()) {
            val out = ArrayList<Match>()
            val need = if (caseSensitive) query else query.lowercase()
            for (r in 0 until lineCount) {
                var line = lineAt(r) ?: continue
                if (!caseSensitive) line = line.lowercase()
                var c = line.indexOf(need)
                while (c >= 0) {
                    out.add(Match(r, c, query.length))
                    c = line.indexOf(need, c + need.length)
                }
            }
            matches = out
            if (resetIdx && matches.isNotEmpty()) {
                idx = matches.indexOfFirst { it.row >= viewportTop }.let { if (it < 0) 0 else it }
                return show()
            }
        }
        return null // show0() ของ JS = report เท่านั้น (count())
    }

    /** ≡ show(): idx ชี้ match ที่แสดง (wrap modulo); คืน match ให้ view (null = ไม่มี) */
    private fun show(): Match? {
        if (matches.isEmpty()) return null
        idx = ((idx + matches.size) % matches.size)
        return matches[idx]
    }

    /** ≡ next(): matches ว่าง = recompute ก่อน (ผู้เรียกส่ง provider เดิม) — idx = idx<0?0:+1 แล้ว show */
    fun next(lineAt: (Int) -> String?, lineCount: Int, viewportTop: Int): Match? {
        if (matches.isEmpty()) recompute(lineAt, lineCount, resetIdx = false, viewportTop = viewportTop)
        idx = if (idx < 0) 0 else idx + 1
        return show()
    }

    /** ≡ prev(): idx<0 → ตัวสุดท้าย; show() wrap ให้ (idx = n → 0) */
    fun prev(lineAt: (Int) -> String?, lineCount: Int, viewportTop: Int): Match? {
        if (matches.isEmpty()) recompute(lineAt, lineCount, resetIdx = false, viewportTop = viewportTop)
        idx = if (idx < 0) matches.size - 1 else idx - 1
        return show()
    }

    /** ≡ report() ของ countEl: query ว่าง = ""; ไม่มี match = "0/0"; ไม่งั้น "i/n" (idx+1) */
    fun count(): String = when {
        query.isEmpty() -> ""
        matches.isEmpty() -> "0/0"
        else -> "${idx + 1}/${matches.size}"
    }

    /** JS: query = input.value แล้ว recompute(true) — setter แยกไว้กันลืม recompute */
    fun setQuery(q: String) { query = q }

    /** ปิด find: ล้าง state ≡ close() (bar display=none — state JS ไม่ได้ล้าง matches แต่ report '') */
    fun reset() { query = ""; matches = emptyList(); idx = -1 }
}
