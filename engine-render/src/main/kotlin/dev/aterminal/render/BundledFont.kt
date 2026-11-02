package dev.aterminal.render

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * round 15-HW (Honor V6): font ที่ bundle จริงใน app assets — ฝั่ง native ต้องมองเห็นตัวเดียวกันกับ
 * @font-face ของ webview (parity) และฆ่ากับดัก round-11: `Typeface.create("FiraCode Nerd Font")`
 * บนเครื่องที่ไม่ได้ *ติดตั้ง* font = Roboto เงียบๆ — family ใน ALIASES จึงถูก load จาก asset ก่อน
 *
 * canonical path = app/src/main/assets/[ASSET_PATH] (subset FiraCode Nerd Font, OFL — ดู assets/fonts/README.txt)
 * ฝั่ง JS อ้าง path เดียวกันใน terminal.html (@font-face) — สองฝั่งต้องชี้ไฟล์เดียวกัน
 */
object BundledFont {
    const val ASSET_PATH = "fonts/terminal.ttf"

    /** family names (lowercase) ที่ map เข้า asset — ≡ ชื่อจริงใน ttf + alias ที่ stack อาจเขียน */
    private val ALIASES = setOf("firacode nerd font", "aterm mono")

    /** pure — JVM-testable, ไม่แตะ Android */
    fun isBundled(family: String): Boolean = family.trim().lowercase() in ALIASES

    @Volatile
    private var cached: Typeface? = null

    /** createFromAsset ครั้งเดียว then memo (fallback: null = ให้ caller ไล่ Typeface.create ตามเดิม) */
    fun lookup(assets: AssetManager, family: String): Typeface? {
        if (!isBundled(family)) return null
        cached?.let { return it }
        return runCatching {
            Typeface.createFromAsset(assets, ASSET_PATH).also { cached = it }
        }.getOrNull()
    }
}
