package dev.aterminal.app

import org.junit.Assert.assertTrue

/**
 * Shared `funBody` สำหรับ source guards (round-18-env/04) — ไฟล์เดียวใช้ทุก guard test
 * (เดิม copy 3 ที่ + cut แค่ 4-space `private`/`internal fun` = body ของ method ใน inner class
 * กินข้าม sibling ทั้ง bridge เช่น SessionBridge.onInput ~150 บรรทัด)
 *
 * cut ถึง member ถัดไปที่เจอก่อน: 4-space (`private`/`internal`/bare/`override fun` ของ outer class)
 * และ 8-space (member ของ inner class: `@JavascriptInterface` / plain `fun`) —
 * ขาดฝั่งใดฝั่งหนึ่ง = assert ขวัดผิด method (บทเรียน review 11-13: `imeTrace` กลืน `fun configure(`)
 *
 * known limitation (accept by design): local `fun` ที่ indent 8-space *ภายใน* method ของ outer class
 * จะถูกเข้าใจผิดเป็น sibling = body คụt (assertTrue ที่อยู่ถัดไป = แดงชี้ผิดที่, assertFalse = เขียวปลอม);
 * helper ไม่ assert ว่า body ปิดด้วย `}` ครบ (signature-missing assert cover rename แล้ว — 11-13 ตัดสินรับไว้)
 */
fun funBody(src: String, signature: String): String {
    val start = src.indexOf(signature)
    assertTrue("ต้องพบ $signature (renamed?)", start >= 0)
    val cuts = listOf(
        "\n    private fun ",
        "\n    internal fun ",
        "\n    fun ",
        "\n    override fun ",
        "\n        @JavascriptInterface",
        "\n        fun ",
    )
        .map { src.indexOf(it, start + 1) }
        .filter { it >= 0 }
    val cut = cuts.minOrNull() ?: src.length
    return src.substring(start, cut)
}
