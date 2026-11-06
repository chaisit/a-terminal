package dev.aterminal.backend.ssh

import dev.aterminal.core.StartupArgs
import dev.aterminal.core.TmuxVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * ticket tmux/02: lab matrix — probe must parse **tmux ของจริง** หลาย version (fixture ครอบคลุมไม่ได้ทุก format)
 *
 * opt-in (CI ปกติ skip): ตั้ง env
 *   TMUX_LAB_SPECS="host:port:user:keypath:expected-version[;...]"
 * ตัวอย่าง (round 24, lab = ThinkStation + docker loopback sshlab-t02-*):
 *   TMUX_LAB_SPECS="127.0.0.1:22:tester:/tmp/aterm-lab-key:tmux 3.6;127.0.0.1:2222:root:/tmp/aterm-lab-key:tmux 3.2a"
 *
 * verify จริง = expected string parse ได้ version ตรงกับที่ probe รายงาน (ทั้ง version + table ผ่าน path เดียวกับ production)
 */
class TmuxLabMatrixTest {

    @Test
    fun `probe reads real tmux versions across the lab matrix`() {
        val specs = System.getenv("TMUX_LAB_SPECS")
        assumeTrue("opt-in lab test — ตั้ง TMUX_LAB_SPECS เพื่อรัน", !specs.isNullOrBlank())
        for (spec in specs.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            val (host, port, user, keyPath, expected) = spec.split(":").let {
                check(it.size == 5) { "spec ต้อง host:port:user:key:version — ได้ '$spec'" }
                Quarters(it[0], it[1].toInt(), it[2], it[3], it[4])
            }
            val key = File(keyPath).readBytes()
            val b = SshBackend(
                host = host, port = port, user = user,
                privateKey = { key }, password = null,
                hostKeyVerifier = { _, _ -> true }, // lab key known-good; TOFU มี test ของตัวเอง
            )
            try {
                runBlocking { b.connect(StartupArgs(null, null, 80, 24)) }
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && b.tmuxCapabilities == null) Thread.sleep(100)
                val caps = b.tmuxCapabilities
                assertNotNull("probe ไม่ได้ version ที่ $host:$port (expected '$expected')", caps)
                assertEquals("version gate ผิดที่ $host:$port", TmuxVersion.parse(expected), caps!!.version)
                println("LAB-MATRIX OK: $host:$port -> ${caps.version} (layoutJson=${caps.layoutJson} osc52=${caps.osc52Clipboard})")
            } finally {
                runCatching { b.close() }
            }
        }
    }

    private data class Quarters(val host: String, val port: Int, val user: String, val key: String, val version: String)
}
