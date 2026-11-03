package dev.aterminal.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogBufferTest {
    private val file = File(Files.createTempDirectory("connlog").toFile(), "connection-log.txt")

    @Test fun `keeps only the newest lines`() {
        val b = ConnectionLogBuffer(file, capacity = 3)
        for (i in 1..5) b.add("line$i")
        assertEquals(listOf("line3", "line4", "line5"), b.dump().lines())
    }

    @Test fun `survives process death by reloading from the file`() {
        ConnectionLogBuffer(file, capacity = 10).add("before death")
        val next = ConnectionLogBuffer(file, capacity = 10)
        next.add("after restart")
        assertEquals(listOf("before death", "after restart"), next.dump().lines())
    }

    @Test fun `file never grows far beyond capacity`() {
        val b = ConnectionLogBuffer(file, capacity = 4)
        for (i in 1..100) b.add("line$i")
        assertTrue(file.readLines().size <= 8)
        assertEquals("line100", file.readLines().last())
    }

    @Test fun `reload keeps the newest lines of an oversized file`() {
        file.writeText((1..20).joinToString("\n", postfix = "\n") { "old$it" })
        val b = ConnectionLogBuffer(file, capacity = 2)
        assertEquals(listOf("old19", "old20"), b.dump().lines())
    }

    @Test fun `unwritable file does not break logging`() {
        val b = ConnectionLogBuffer(File(file.parentFile, "missing/log.txt"), capacity = 2)
        b.add("kept in memory")
        assertEquals("kept in memory", b.dump())
    }
}
