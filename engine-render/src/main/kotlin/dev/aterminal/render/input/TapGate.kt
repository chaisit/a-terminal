package dev.aterminal.render.input

import kotlin.math.hypot

/**
 * round 15-HW (Honor V6): "scroll ไม่ใช่การพิมพ์" — gate กลางที่ตัดสินว่า gesture = **tap** หรือไม่
 * โดย ไม่รู้จัก IME เลย (caller ตัดสินใจเอง — ≡ สไตล์ TouchKernel ที่ pure แล้ว view glue ใช้)
 *
 * Tap = นิ้วเดียวตลอด gesture + ระยะจากจุดแตะ ≤ slop + ปล่อยก่อน longPressTimeout
 *  - ลาก (scroll) = NoTap | สองนิ้ว (scroll by user) = NoTap | long-press (select) = NoTap — intentional
 *  - time ทั้งหมดส่งเข้าเป็น parameter (uptimeMillis จาก MotionEvent) = JVM-testable, ไม่มี Android type
 */
class TapGate(private val slopPx: Float, private val longPressMs: Long) {

    enum class Result { Tap, NoTap }

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var pointers = 0
    private var moved = false
    private var multiTouch = false

    /** ACTION_DOWN (นิ้วแรก) — เริ่ม gesture ใหม่เสมอ */
    fun down(x: Float, y: Float, now: Long) {
        active = true
        pointers = 1
        moved = false
        multiTouch = false
        startX = x; startY = y; downAt = now
    }

    /** ACTION_POINTER_DOWN — นิ้วที่สอง (หรือมากกว่า) = gesture นี้ไม่ใช่ tap แล้ว */
    fun pointerDown() {
        if (!active) return
        pointers++
        if (pointers > 1) multiTouch = true
    }

    /** ACTION_POINTER_UP — กัน bookkeeping เพี้ยน (ไม่ใช้ตัดสิน: multiTouch ติดแล้วไม่หลุด) */
    fun pointerUp() {
        if (active && pointers > 0) pointers--
    }

    /** ACTION_MOVE — พิกัด origin เดียวกับ down() (caller ระวัง: root-space vs view-space ห้ามปน) */
    fun move(x: Float, y: Float) {
        if (!active) return
        if (hypot((x - startX).toDouble(), (y - startY).toDouble()) > slopPx) moved = true
    }

    /**
     * round 15-HW-2 (Honor V6): "gesture สิ้นสภาพ tap แล้วหรือยัง" — real-time ระหว่าง gesture (ก่อน UP)
     * caller ใช้ early-suppress IME: สองนิ้ว/ลาก/longpress = แน่นอนว่าไม่ใช่ tap แล้ว → IME ที่ลอบเด้ง
     * กลาง gesture ต้องถูกเก็บทันที ไม่ต้องรอ UP (keyboard ทับนิ้ว = gesture ตายบนเครื่องจริง)
     * pure function ของ state + now (longpress ใช้เวลา — MOVE มี eventTime ให้ผู้เรียกส่งเข้ามา)
     */
    fun busted(now: Long): Boolean =
        active && (multiTouch || moved || (now - downAt) >= longPressMs)

    /**
     * round 15-HW-4 (Honor V6): busted เฉพาะโหมด "นิ้วนิ่งนาน" — long-press select จริง (ไม่ลาก ไม่สองนิ้ว)
     * จำเป็นเพราะ caller ยิงจาก timer ไม่ได้ยิงจาก MotionEvent: นิ้วนิ่งหลัง DOWN = stream เงียบ,
     * busted() ไม่มีโอกาส eval แต่ Chromium/JS long-press timer ทำงานตามเวลาจริง → ต้องถามเวลาเอาเอง
     * และ policy ต่างกัน: expired (select) = เก็บ IME แม้ user เปิดเอง, moved/multiTouch (scroll) = ห้าม
     */
    fun expired(now: Long): Boolean =
        active && !multiTouch && !moved && (now - downAt) >= longPressMs

    /** ACTION_UP — คำตัดสินเดียวของ gesture (จบ gesture เสมอ) */
    fun up(now: Long): Result {
        val tap = active && !multiTouch && !moved && (now - downAt) < longPressMs
        active = false
        return if (tap) Result.Tap else Result.NoTap
    }

    /** ACTION_CANCEL — ทิ้ง gesture (≡ JS touchcancel semantics) */
    fun cancel() {
        active = false
    }
}
