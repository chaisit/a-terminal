package dev.aterminal.app

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update Check — pure core (round 23 amendment)
 *
 * Release Feed เดียว = GitHub Releases ของ repo สาธารณะ (ตัด draft/prerelease)
 * local ≥ published = "พร้อมอยู่แล้ว" เงียบสนิท → build ทดสอบที่นำหน้า release ไม่ถูกเตือนย้อนหลัง
 * ตรรกะทั้งหมดเป็น JVM-test (org.json มีใน android.jar runtime; test dependency มีแล้วตั้งแต่ round 13 T4)
 * — fetch เป็น funnel เดียวบาง ๆ ไม่วัดผลใน unit test (network = device gate)
 */
object UpdateCheck {

    /**
     * repo สาธารณะเท่านั้น — monorepo/internal ห้ามโผล่ใน URL นี้ (UpdateCheckTest feed guard)
     * F4 (review): per_page=100 = เพดาน page เดียว — cumulative ครอบคลุม user ที่ข้ามได้มากสุด ~100 releases
     * (ปัจจุบันทั้งโปรเจกต์ <20 — ถ้า feed โตเกินนั้นเมื่อไร ค่อย pagination)
     */
    const val FEED_URL = "https://api.github.com/repos/chaisit/a-terminal/releases?per_page=100"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class Release(
        val tag: String,
        val body: String,
        val url: String,
        val draft: Boolean,
        val prerelease: Boolean,
    )

    /** GET list endpoint (list ไม่ใช่ /latest — Cumulative Changelog ต้องการ body ทุกตัวระหว่างทาง, Q5) */
    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "A-Terminal") // GitHub API ต้องการ UA — ไม่มี = 403
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parseReleases(text: String): List<Release> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Release(
                tag = o.optString("tag_name", ""),
                body = o.optString("body", ""), // GitHub ส่ง body=null ได้ → optString กลายเป็น "" ให้เอง
                url = o.optString("html_url", ""),
                draft = o.optBoolean("draft", false),
                prerelease = o.optBoolean("prerelease", false),
            )
        }
    }

    /**
     * "v0.5.0" | "0.5.0" | "0.6.0-hw" → [0,5,0] (suffix ทิ้ง — hwtest/dev build เทียบเลขหลักได้);
     * parse ไม่ได้ → null = ผู้เรียกห้าม nag (silence ไม่ใช่ error)
     */
    fun parseVersion(text: String?): IntArray? {
        if (text == null) return null
        val m = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(text) ?: return null
        return intArrayOf(
            m.groupValues[1].toInt(),
            m.groupValues[2].toIntOrNull() ?: 0,
            m.groupValues[3].toIntOrNull() ?: 0,
        )
    }

    /** numeric compare ทั้งสามหลัก (0.5.10 > 0.5.9 — lexical จะตอบผิด); null ฝั่งไหน = false เสมอ */
    fun isNewer(candidate: IntArray?, current: IntArray?): Boolean {
        if (candidate == null || current == null) return false
        for (i in 0 until 3) {
            if (candidate[i] != current[i]) return candidate[i] > current[i]
        }
        return false // equal = silent ตาม Q6 (local ≥ published ต้องเงียบ)
    }

    /**
     * release ที่ "ใหม่กว่าจริง" เท่านั้น (ตัด draft/prerelease/equal/older) เรียงใหม่→เก่า
     * sort ทีละหลัก (major→minor→patch) — ห้าม sort แบบ string (lexical: "0.9.0" > "0.10.0")
     */
    fun eligibleNewer(releases: List<Release>, currentVersion: String?): List<Release> {
        val cur = parseVersion(currentVersion) ?: return emptyList()
        return releases
            .filter { !it.draft && !it.prerelease && it.tag.isNotEmpty() }
            .mapNotNull { r -> parseVersion(r.tag)?.let { r to it } }
            .filter { isNewer(it.second, cur) }
            .sortedWith( // element-wise: [0,10,0] > [0,9,0] ถูก (ห้าม lexical แบบ joinToString)
                compareByDescending<Pair<Release, IntArray>> { it.second[0] }
                    .thenByDescending { it.second[1] }
                    .thenByDescending { it.second[2] }
            )
            .map { it.first }
    }

    /** Cumulative Changelog: ทุก release ใหม่กว่าที่ติดตั้ง เรียงใหม่→เก่า (Q5 — ข้ามรอบไม่พลาดเนื้อหา) */
    fun cumulativeChangelog(newer: List<Release>): String =
        newer.joinToString("\n\n────────\n\n") { r ->
            val body = stripMarkdown(r.body)
            if (body.isEmpty()) r.tag else "${r.tag}\n\n$body"
        }

    /**
     * light markdown strip (Q10) — dialog เป็น plain Text ไม่ใช่ renderer:
     * heading `#`, `**bold**`, backticks, link → `text (url)`; bullet `- ` คงไว้ (อ่านรู้เรื่องใน plain)
     */
    fun stripMarkdown(body: String): String {
        var s = body.replace("\r\n", "\n")
        s = Regex("""^(#{1,6})\s+""", RegexOption.MULTILINE).replace(s, "")
        s = s.replace("**", "")
        s = s.replace("`", "")
        s = Regex("""\[([^\]]*)]\(([^)]+)\)""").replace(s) { m ->
            val t = m.groupValues[1]; val u = m.groupValues[2]
            if (t.isEmpty() || t == u) u else "$t ($u)"
        }
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    /**
     * auto path เท่านั้นที่ throttle (manual ไม่ถูกบล็อก — Q1/Q3)
     * F7 (review, parked): clock rollback → เช็คซ้ำได้ แต่ bounded — stamp เขียนใหม่ด้วย now ทุก attempt
     * = สมุดเวลาเพี้ยนตลอดก็ self-heal หลัง attempt แรก (worst case = fetch เกิน 1 ครั้งต่อช่วง clock กระโดด
     * ไม่เข้าใกล้ 60 req/hr limit) — monotonic clock gate = overkill สำหรับฟีเจอร์ "ด่วนๆเล็กๆ"
     */
    fun shouldAutoCheck(updateChecks: Boolean, lastCheckAt: Long?, now: Long, minIntervalMs: Long = DAY_MS): Boolean {
        if (!updateChecks) return false
        if (lastCheckAt == null) return true
        if (now < lastCheckAt) return true
        return now - lastCheckAt >= minIntervalMs
    }

    /** dismissed ครอบ release นั้นตัวเดียว (Later ≠ ปิด Update Check) */
    fun shouldNotify(candidateTag: String, dismissedUpdateTag: String?): Boolean =
        candidateTag != dismissedUpdateTag
}
