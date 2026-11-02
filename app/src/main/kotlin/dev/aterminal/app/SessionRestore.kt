package dev.aterminal.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * T4 (round 13): session restore — เก็บ "โครง tab + ai diary ที่จะ reconnect" ข้าม process death
 *  - สิ่งเดียวที่ stable ข้าม launch = Profile.guid → snapshot เก็บ guid, ห้ามเก็บ credential/pin
 *    (ความลับ stays ใน SecretsStore; admission rule ฝั่ง MainActivity เป็นผู้ตรวจ pin)
 *  - org.json (ไม่มี serialization plugin ใน :app — decision ในแผน) — JVM-test ผ่าน testImplementation org.json:json
 *  - schema v1: {v:1, focusedTab:Int, tabs:[{slots:[guid...], orientation:"H"|"V", fraction:Float, focused:Int}]}
 *  - snapshot ที่ decode ผ่านแล้ว = ผ่าน cap-trim แล้ว (trim ก่อน encode เสมอ — decode ไม่ trim ซ้ำ)
 */
object SessionRestore {

    const val VERSION = 1
    const val MAX_PANES = 4 // memory valve ชนะ grid (PaneLru=5 — เว้น 1 ที่ว่างให้ boot-time churn)

    data class RestoreTab(
        val slots: List<String>,          // guid per pane (เรียงตาม panes model); ทุกตัว everConnected แล้ว
        val orientation: SplitOrientation,
        val fraction: Float,
        val focused: Int,                 // index ใน slots
    )

    data class Snapshot(val tabs: List<RestoreTab>, val focusedTab: Int)

    fun encode(s: Snapshot): String {
        val tabs = JSONArray()
        for (t in s.tabs) {
            val o = JSONObject()
            o.put("slots", JSONArray(t.slots))
            o.put("orientation", if (t.orientation == SplitOrientation.HORIZONTAL) "H" else "V")
            o.put("fraction", t.fraction.toDouble())
            o.put("focused", t.focused)
            tabs.put(o)
        }
        return JSONObject()
            .put("v", VERSION)
            .put("focusedTab", s.focusedTab)
            .put("tabs", tabs)
            .toString()
    }

    /** ผุ/ผิด version/ว่าง = null (boot path เดิม) — ห้าม throw เข้า onCreate */
    fun decode(text: String): Snapshot? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v", -1) != VERSION) return null
        val arr = o.optJSONArray("tabs") ?: return null
        val tabs = ArrayList<RestoreTab>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: return null
            val slotsArr = t.optJSONArray("slots") ?: return null
            val slots = ArrayList<String>()
            for (j in 0 until slotsArr.length()) {
                val g = slotsArr.optString(j, "")
                if (g.isEmpty()) return null // v1: ไม่มี slot ว่าง (filter ที่ collector แล้ว)
                slots.add(g)
            }
            if (slots.isEmpty() || slots.size > TerminalTab.MAX_PANES) return null
            val o1 = t.optString("orientation", "H")
            if (o1 != "H" && o1 != "V") return null
            val f = t.optDouble("fraction", 0.5).toFloat()
            if (f < 0.1f || f > 0.9f) return null
            val foc = t.optInt("focused", 0).coerceIn(0, slots.size - 1)
            tabs.add(RestoreTab(slots, if (o1 == "H") SplitOrientation.HORIZONTAL else SplitOrientation.VERTICAL, f, foc))
        }
        if (tabs.isEmpty()) return null
        val ft = o.optInt("focusedTab", 0).coerceIn(0, tabs.size - 1)
        Snapshot(tabs, ft)
    }.getOrNull()

    /**
     * memory valve: pane รวมทุก tab ต้อง ≤ cap — ตัดทิ้งจากท้าย (tab หลัง/pane ขวาสุดก่อน)
     * tab ที่เหลือศูนย์ pane = หายทั้ง tab; focusedTab clamp ตาม
     */
    fun trimToPaneCap(s: Snapshot, cap: Int = MAX_PANES): Snapshot {
        val tabs = s.tabs.toMutableList()
        while (tabs.sumOf { it.slots.size } > cap && tabs.isNotEmpty()) {
            val last = tabs.removeAt(tabs.size - 1)
            if (last.slots.size > 1) {
                val slots = last.slots.dropLast(1)
                tabs.add(last.copy(slots = slots, focused = last.focused.coerceAtMost(slots.size - 1)))
            }
        }
        if (tabs.isEmpty()) return Snapshot(emptyList(), 0)
        return Snapshot(tabs, s.focusedTab.coerceIn(0, tabs.size - 1))
    }
}
