package dev.aterminal.app

/**
 * ตัดสินว่า foreground service ต้องทำงานไหม — ต้องอยู่ตลอดช่วง reconnect ด้วย
 * เพราะเริ่ม service ใหม่จาก background ไม่ได้ (Android 12+) และไม่มี service ระบบจะบล็อก network ใน 5 วินาที
 */
object KeepAlivePolicy {
    data class Decision(val run: Boolean, val wakeLock: Boolean, val text: String)

    fun decide(connected: Int, connecting: Int, reconnecting: Int, keepCpuAwake: Boolean): Decision {
        val run = connected + connecting + reconnecting > 0
        val sessions = "$connected session${if (connected == 1) "" else "s"} connected"
        val text = when {
            connected > 0 && reconnecting > 0 -> "$sessions, reconnecting others…"
            connected > 0 -> sessions
            reconnecting > 0 -> "Reconnecting…"
            connecting > 0 -> "Connecting…"
            else -> ""
        }
        return Decision(run, run && keepCpuAwake, text)
    }
}
