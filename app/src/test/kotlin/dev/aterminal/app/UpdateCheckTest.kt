package dev.aterminal.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Update Check core (round 23 amendment):
 * Release Feed = GitHub Releases สาธารณะ, Update Notice ครั้งเดียวต่อ release,
 * Cumulative Changelog ใหม่→เก่า; local ≥ published = เงียบ (build ทดสอบไม่ถูกเตือนย้อนหลัง)
 */
class UpdateCheckTest {

    private fun releasesJson(vararg objs: org.json.JSONObject): String =
        JSONArray().apply { objs.forEach { put(it) } }.toString()

    private fun release(
        tag: String,
        body: String? = "notes for $tag",
        url: String = "https://github.com/chaisit/a-terminal/releases/tag/$tag",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<Pair<String, String>> = emptyList(), // (asset name, browser_download_url)
    ) = org.json.JSONObject().apply {
        put("tag_name", tag)
        if (body != null) put("body", body)
        put("html_url", url)
        put("draft", draft)
        put("prerelease", prerelease)
        if (assets.isNotEmpty()) {
            put("assets", JSONArray().apply {
                assets.forEach { (name, link) ->
                    put(org.json.JSONObject().put("name", name).put("browser_download_url", link))
                }
            })
        }
    }

    // --- parseReleases ---

    @Test fun parsesTagBodyUrlAndFlags() {
        val text = releasesJson(release("v0.6.0", body = "**new** stuff", prerelease = true))
        val r = UpdateCheck.parseReleases(text).single()
        assertEquals("v0.6.0", r.tag)
        assertEquals("**new** stuff", r.body)
        assertEquals("https://github.com/chaisit/a-terminal/releases/tag/v0.6.0", r.url)
        assertTrue(r.prerelease)
        assertFalse(r.draft)
    }

    @Test fun missingBodyBecomesEmptyString() {
        val text = releasesJson(release("v0.6.0", body = null))
        assertEquals("", UpdateCheck.parseReleases(text).single().body)
    }

    @Test fun emptyArrayParsesToEmptyList() {
        assertEquals(emptyList<UpdateCheck.Release>(), UpdateCheck.parseReleases("[]"))
    }

    @Test fun apkAssetUrlParsedNonApkAssetsIgnored() {
        val text = releasesJson(
            release(
                "v0.6.0",
                assets = listOf(
                    "SHA256SUMS.txt" to "https://github.com/download/sums",
                    "app-release.apk" to "https://github.com/download/apk",
                ),
            )
        )
        assertEquals("https://github.com/download/apk", UpdateCheck.parseReleases(text).single().apkUrl)
    }

    @Test fun noApkAssetMeansEmptyApkUrl() { // dialog fallback = View release (ห้ามชี้ sum file เป็น download)
        assertEquals("", UpdateCheck.parseReleases(releasesJson(release("v0.6.0", assets = listOf("sums.txt" to "https://x/sums")))).single().apkUrl)
        assertEquals("", UpdateCheck.parseReleases(releasesJson(release("v0.6.0"))).single().apkUrl)
    }

    // --- parseVersion ---

    @Test fun parsesTagAndPlainVersionsWithOptionalVPrefix() {
        assertTrue(updateCheckNewer("v0.6.0", "0.5.0")) // prefix tolerated both sides
    }

    @Test fun shortVersionPadsWithZero() {
        assertTrue(updateCheckNewer("0.6", "0.5.9"))
    }

    @Test fun suffixedVersionTakesNumericPrefix() {
        // hwtest/dev builds อาจติด suffix — เลขหลักยังเทียบได้
        assertTrue(updateCheckNewer("0.6.0-hw", "0.5.0"))
    }

    @Test fun junkOrNullVersionUnparseable() {
        assertNull(UpdateCheck.parseVersion("beta"))
        assertNull(UpdateCheck.parseVersion(null))
        assertNull(UpdateCheck.parseVersion(""))
    }

    // --- isNewer ---

    @Test fun comparesNumericallyNotLexically() {
        assertTrue(updateCheckNewer("0.5.10", "0.5.9")) // lexicographic would say false
        assertFalse(updateCheckNewer("0.5.0", "0.5.0")) // equal = silent (Q6 ruling)
        assertFalse(updateCheckNewer("0.4.9", "0.5.0"))
        assertTrue(updateCheckNewer("1.0.0", "0.99.99"))
    }

    @Test fun nullVersionNeverNewer() {
        assertFalse(UpdateCheck.isNewer(null, intArrayOf(0, 5, 0)))
        assertFalse(UpdateCheck.isNewer(intArrayOf(0, 5, 0), null))
    }

    private fun updateCheckNewer(candidate: String, current: String): Boolean =
        UpdateCheck.isNewer(UpdateCheck.parseVersion(candidate), UpdateCheck.parseVersion(current))

    // --- eligibleNewer ---

    @Test fun filtersDraftPrereleaseAndNonNewer_sortsNewestFirst() {
        val releases = UpdateCheck.parseReleases(
            releasesJson(
                release("v0.4.0"), // older — out
                release("v0.5.0"), // equal — out
                release("v0.7.0", draft = true),
                release("v0.6.1", prerelease = true),
                release("v0.6.0"),
                release("v0.5.1"),
            )
        )
        val eligible = UpdateCheck.eligibleNewer(releases, "0.5.0")
        assertEquals(listOf("v0.6.0", "v0.5.1"), eligible.map { it.tag })
    }

    @Test fun unparseableCurrentVersionMeansNoCandidates() {
        // local version เสีย = ห้าม nag (build แปลกๆ เงียบสนิท ตาม Q6)
        val releases = UpdateCheck.parseReleases(releasesJson(release("v9.9.9")))
        assertEquals(emptyList<UpdateCheck.Release>(), UpdateCheck.eligibleNewer(releases, "weird"))
        assertEquals(emptyList<UpdateCheck.Release>(), UpdateCheck.eligibleNewer(releases, null))
    }

    // --- cumulativeChangelog ---

    @Test fun newestFirst_withTagHeaders_andAllBodies() {
        val newer = UpdateCheck.parseReleases(
            releasesJson(
                release("v0.6.0", body = "FEATURE-BODY-NEW"),
                release("v0.5.1", body = "FEATURE-BODY-OLD"),
            )
        ) // JSON order = newest-first ตาม contract ของ eligibleNewer
        val text = UpdateCheck.cumulativeChangelog(newer)
        assertTrue(text.startsWith("v0.6.0"))
        val iNew = text.indexOf("FEATURE-BODY-NEW")
        val iOld = text.indexOf("FEATURE-BODY-OLD")
        assertTrue(iNew in 0 until iOld) // new → old ordering, both present (Q5 cumulative)
        assertTrue(text.contains("v0.5.1"))
    }

    // --- stripMarkdown (Q10: light strip, plain Text เท่านั้น) ---

    @Test fun stripsHeadingsBoldBackticks_linksBecomeTextParenUrl() {
        val out = UpdateCheck.stripMarkdown(
            "## Title\n\n**bold** and `code` and [site](https://x.y/z)\n- bullet stays\n"
        )
        assertTrue(out.startsWith("Title"))
        assertTrue(out.contains("bold and code"))
        assertTrue(out.contains("site (https://x.y/z)"))
        assertTrue(out.contains("- bullet stays"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("`"))
        assertFalse(out.contains("##"))
    }

    @Test fun collapsesExcessBlankLines() {
        val out = UpdateCheck.stripMarkdown("a\n\n\n\n\nb")
        assertEquals("a\n\nb", out)
    }

    // --- throttle (auto ≤1×/วัน, Q1) ---

    private val day = 24L * 60 * 60 * 1000

    @Test fun autoChecksOnlyWhenEnabledAndStale() {
        val now = 1_700_000_000_000L
        assertFalse(UpdateCheck.shouldAutoCheck(false, null, now)) // opt-out เคารพทุกกรณี
        assertTrue(UpdateCheck.shouldAutoCheck(true, null, now)) // ไม่เคยเช็ค
        assertFalse(UpdateCheck.shouldAutoCheck(true, now - 23 * 60 * 60 * 1000, now)) // 23 ชม. = ยังไม่
        assertTrue(UpdateCheck.shouldAutoCheck(true, now - day, now)) // ครบ 24 ชม. พอดี = เช็ค
    }

    @Test fun clockRollbackSelfHeals() {
        val now = 1_700_000_000_000L
        // stamp อยู่อนาคต (clock rollback หลังเช็ค) = ต้องเช็คใหม่ได้ ไม่ใช่ถูกบล็อกตลอด
        assertTrue(UpdateCheck.shouldAutoCheck(true, now + day, now))
    }

    // --- dismissed (Update Notice ครั้งเดียวต่อ release, Q2) ---

    @Test fun dismissedTagSuppressesOnlyThatRelease() {
        assertFalse(UpdateCheck.shouldNotify("v0.6.0", "v0.6.0"))
        assertTrue(UpdateCheck.shouldNotify("v0.6.1", "v0.6.0")) // release ใหม่กว่า = เตือนได้ตามปกติ
        assertTrue(UpdateCheck.shouldNotify("v0.6.0", null))
    }

    // --- feed guard: URL ต้องชี้ repo สาธารณะเท่านั้น (ข้อมูลภายใน/ชื่อ repo ห้ามหลุด) ---

    @Test fun feedUrlPointsAtPublicRepoReleases() {
        assertTrue(UpdateCheck.FEED_URL.startsWith("https://api.github.com/repos/"))
        assertTrue(UpdateCheck.FEED_URL.contains("chaisit/a-terminal"))
        assertFalse(UpdateCheck.FEED_URL.contains("aterminal-engine"))
        assertTrue(UpdateCheck.FEED_URL.contains("releases"))
    }
}
