package dev.aterminal.render

import org.junit.Assert.*
import org.junit.Test

/** stack parsing ≡ CSS font-family — ผู้ใช้ตั้ง fontFace ตาม WT (stack เต็ม) ต้อง parse ได้ตรงลำดับ */
class FontStackTest {
    @Test fun `default WT stack parses in order`() {
        assertEquals(
            listOf("Cascadia Mono", "JetBrains Mono", "monospace"),
            FontStack.families("Cascadia Mono, JetBrains Mono, monospace"),
        )
    }

    @Test fun `null blank and empty items drop`() {
        assertEquals(emptyList<String>(), FontStack.families(null))
        assertEquals(emptyList<String>(), FontStack.families("   "))
        assertEquals(listOf("Mono"), FontStack.families("Mono , , "))
    }
}
