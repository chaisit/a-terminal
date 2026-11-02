package dev.aterminal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** SettingsSync + SettingsLoader round-trip — terminal-core test dir แรก (JVM ล้วน) */
class SettingsSyncTest {

    private val sample = SettingsLoader.render(
        Settings(
            defaultProfile = "a",
            profiles = ProfilesObject(list = listOf(Profile(guid = "a", name = "A", type = "echo"))),
            schemes = emptyList(),
            keybindings = emptyList(),
        )
    )

    @Test
    fun `render-parse round-trip คง field ครบ + unknown keys ไม่พัง (forward-compat WT)`() {
        val s = SettingsLoader.parse(sample)
        assertEquals("a", s.defaultProfile)
        assertEquals("A", s.profiles.list.single().name)
        // field ที่ WT ปล่อยมาก่อนหน้าเรา — ignoreUnknownKeys ต้องกลืนได้
        val future = sample.replace("{\"", """{"wtFutureFlag":true,"""")
        assertEquals("a", SettingsLoader.parse(future).defaultProfile)
    }

    @Test
    fun `hash ตรง lastWrite = Ignore (self-write ไม่ trigger reload)`() {
        val h = SettingsSync.sha256(sample)
        assertEquals(SettingsSync.Decision.Ignore, SettingsSync.decide(sample, h))
    }

    @Test
    fun `content ต่าง = Reload, parse พัง = Invalid, ยังไม่เคยเขียนก็ Reload`() {
        val h = SettingsSync.sha256(sample)
        assertEquals(SettingsSync.Decision.Reload, SettingsSync.decide(sample + " ", h))
        assertNotEquals(h, SettingsSync.sha256(sample + " "))
        assertEquals(SettingsSync.Decision.Invalid, SettingsSync.decide("{not json", h))
        assertEquals(SettingsSync.Decision.Reload, SettingsSync.decide(sample, null))
        assertEquals(SettingsSync.Decision.Reload, SettingsSync.decide("{}", null)) // ทุก field มี default = valid กลวง ๆ
        assertEquals(SettingsSync.Decision.Invalid, SettingsSync.decide("""{"profiles": 5}""", null)) // type ผิด = throw
    }
}
