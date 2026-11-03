package dev.aterminal.app

import dev.aterminal.core.BackendError

/**
 * re-pin (round 16): policy สำหรับกรณี host key ของ host ที่ pin แล้วเปลี่ยน (เช่น server ถูกลงใหม่)
 *
 * ปัญหาเดิม: HOST_KEY_MISMATCH = fatal ถาวร ไม่มีทางล้าง pin → ผู้ใช้ติดตาย (ลบ profile ก็ไม่ช่วย)
 * กติกาที่ user อนุมัติ: ต้องแสดง fingerprint เก่า+ใหม่ให้ยืนยันก่อน — ห้ามเปลี่ยน pin เงียบ ๆ
 * และ dialog ต้องมาจาก manual dial เท่านั้น (auto countdown ห้ามเด้ง dialog ทุกชนิด — กฎเดิม)
 *
 * pure JVM (no Android) — ข้อความเตือนเป็น security-critical จึงอยู่ที่นี่พร้อม test
 */
object RepromptPolicy {

    /** true = เด้ง re-pin dialog ได้; manualDial = user กดเชื่อมต่อเอง (ไม่ใช่ auto countdown) */
    fun shouldPrompt(code: BackendError.Code?, manualDial: Boolean): Boolean =
        code == BackendError.Code.HOST_KEY_MISMATCH && manualDial

    /** รูปแบบ string เดียวของ pin ใน SecretsStore: "algo SHA256:..." (verifier เทียบ exact string) */
    fun pinValue(algo: String, fingerprint: String): String = "$algo $fingerprint"

    /** แยก pin ที่ store ไว้เป็น (algo, fingerprint); ข้อมูลผิดรูป = null (ห้ามเด้ง dialog ครึ่ง ๆ กลาง ๆ) */
    fun parsePin(stored: String?): Pair<String, String>? {
        val s = stored ?: return null
        val i = s.indexOf(' ')
        if (i <= 0 || i == s.length - 1) return null
        return s.substring(0, i) to s.substring(i + 1)
    }

    /** ข้อความ re-pin warning — ต้องมี host:port + fingerprint ทั้งคู่ + คำเตือน MITM ชัดเจน */
    fun warningMessage(
        host: String, port: Int,
        oldAlgo: String, oldFingerprint: String,
        newAlgo: String, newFingerprint: String,
    ): String =
        "The host key for $host:$port no longer matches the pinned key.\n\n" +
            "Pinned ($oldAlgo): SHA256: ${oldFingerprint.removePrefix("SHA256:")}\n" +
            "Server now ($newAlgo): SHA256: ${newFingerprint.removePrefix("SHA256:")}\n\n" +
            "If you rebuilt or reinstalled this server, seeing a new key is expected. " +
            "Otherwise this can mean someone is intercepting your connection (man-in-the-middle).\n\n" +
            "Check the new fingerprint against your server " +
            "(ssh-keygen -lf /etc/ssh/ssh_host_*key.pub) before accepting."
}
