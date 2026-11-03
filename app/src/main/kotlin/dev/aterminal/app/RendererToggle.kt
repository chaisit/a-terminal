package dev.aterminal.app

/**
 * เมนู Renderer (round 18): ค่าถัดไปของ Settings.engine เมื่อผู้ใช้แตะสลับ
 *
 * pure JVM — rule เดียวกับที่ MainActivity ใช้เลือก renderer: มีเพียง "native" เท่านั้นที่ means native
 * (ค่าอื่น/garbage = webview) จึง toggle เป็น native ได้เสมอจาก state non-native
 */
object RendererToggle {
    fun next(current: String): String = if (current == "native") "webview" else "native"
}
