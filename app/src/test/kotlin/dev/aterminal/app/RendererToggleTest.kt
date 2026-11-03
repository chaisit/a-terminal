package dev.aterminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * เมนู Renderer (round 18): สลับ engine ระหว่างทดสอบ native — ค่าที่ store ต้องเป็น canonical
 * ("native"/"webview") เสมอ เพราะ MainActivity เทียบ `== "native"` ตอนสร้าง pane
 */
class RendererToggleTest {

    @Test fun `webview becomes native`() {
        assertEquals("native", RendererToggle.next("webview"))
    }

    @Test fun `native becomes webview`() {
        assertEquals("webview", RendererToggle.next("native"))
    }

    @Test fun `garbage setting value toggles to native (only native means native)`() {
        assertEquals("native", RendererToggle.next(""))
        assertEquals("native", RendererToggle.next("xterm"))
    }
}
