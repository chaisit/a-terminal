package dev.aterminal.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicTextTest {
    private val dir: File = Files.createTempDirectory("atomic").toFile()

    @Test fun `writes new and replaces existing without leaving temp files`() {
        val f = File(dir, "settings.json")
        AtomicText.write(f, "one")
        AtomicText.write(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("settings.json"), dir.list()!!.toList())
    }

    @Test fun `failed write keeps the old content`() {
        val f = File(dir, "settings.json")
        f.writeText("keep")
        val bad = File(dir, "missing/settings.json")
        assertThrows(Exception::class.java) { AtomicText.write(bad, "x") }
        assertEquals("keep", f.readText())
        assertFalse(bad.exists())
    }
}
