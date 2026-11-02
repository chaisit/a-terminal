package dev.aterminal.app

/**
 * T3 (round 12): นโยบาย auto-reconnect ต่อ pane — pure class ไม่มี Android dependency (JVM test ได้)
 *
 * กติกา (user อนุมัติ: auto backoff + banner):
 *  - backoff 1→2→4→8→16→30s (cap 30, no jitter — app user เดียว ไม่ใช่ thundering herd)
 *  - retryable=false (AUTH_FAILED / HOST_KEY_MISMATCH = MITM) → **หยุดถาวร** ห้าม dial ซ้ำเงียบ
 *    (ผู้เรียกเป็นผู้ส่ง flag นี้จาก BackendError/CloseReason — policy ไม่ตีความเอง)
 *  - user long-press "stop" → หยุดจนถึง CONNECTED ครั้งถัดไป (noteConnected ปลดให้)
 *  - CONNECTED → attempts = 0 (หลุดรอบใหม่เริ่ม count ใหม่)
 */
class ReconnectPolicy {

    /** จำนวนครั้งที่ auto-reconnect ไปแล้วในรอบหลุดนี้ (สำหรับแสดง (n) บน banner) */
    var attempts = 0
        private set

    private var userStopped = false

    val stoppedByUser: Boolean get() = userStopped

    /** ผู้ใช้กด "หยุด auto" เอง — ห้าม retry จนกว่าจะ CONNECTED ใหม่ */
    fun userStop() { userStopped = true }

    /** connect สำเร็จ — ล้างทั้ง count และ user-stop */
    fun noteConnected() {
        attempts = 0
        userStopped = false
    }

    /**
     * ตัดสินครั้งถัดไป: null = หยุด (fatal หรือไม่ user ก็ stop), ไม่งั้น = delay ms ก่อน dial
     * **side-effect:** นับ attempts เพิ่ม (เรียกเฉพาะตอนจะ dial จริง)
     */
    fun nextDelayMs(retryable: Boolean): Long? {
        if (!retryable || userStopped) return null
        val d = when (attempts) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 16_000L
            else -> 30_000L
        }
        attempts++
        return d
    }
}
