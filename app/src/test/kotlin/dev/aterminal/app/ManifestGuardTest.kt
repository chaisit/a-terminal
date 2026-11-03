package dev.aterminal.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestGuardTest {
    /** session ผูกกับ Activity: รายการที่หายไปรายการเดียว = เปลี่ยนค่านั้นในระบบแล้ว SSH ทุก session หลุด */
    @Test fun `activity handles every configuration change itself`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val value = Regex("""android:configChanges="([^"]+)"""").find(manifest)!!.groupValues[1]
        val expected = setOf(
            "mcc", "mnc", "locale", "touchscreen", "keyboard", "keyboardHidden", "navigation",
            "screenLayout", "fontScale", "uiMode", "orientation", "density", "screenSize",
            "smallestScreenSize", "layoutDirection", "colorMode", "grammaticalGender",
            "fontWeightAdjustment",
        )
        assertEquals(emptySet<String>(), expected - value.split("|").toSet())
    }
}
