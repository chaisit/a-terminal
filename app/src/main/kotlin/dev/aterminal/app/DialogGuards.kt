package dev.aterminal.app

import android.app.Activity
import android.app.AlertDialog

// R5 (F-4) — crash จริงบน device (round 9, 20:28): boot dialog `postDelayed(400)` landing
// บน activity ที่กำลังตาย (relaunch) = WindowManager$BadTokenException
// กติกา: ทุก AlertDialog.show() ใน app ต้องผ่าน showGuarded — dialog ถึง activity ตาย = ทิ้ง (drop), ไม่ใช่ queue
// (toast ไม่ crash แต่ guard ให้ symmetry)

/** pure logic แยกไว้ unit test ได้ (Activity จริง mock ไม่ได้ใน plain-JVM setup) */
internal fun canShowDialog(finishing: Boolean, destroyed: Boolean): Boolean = !finishing && !destroyed

/** show() เฉพาะ activity ที่ยังรอด — else no-op */
internal fun AlertDialog.Builder.showGuarded(on: Activity): AlertDialog? =
    if (canShowDialog(on.isFinishing, on.isDestroyed)) show() else null

/**
 * showGuarded สำหรับ dialog ที่ override ปุ่ม positive เอง (สร้างไว้ก่อนแล้ว setOnShowListener) —
 * guard เดียวกันเป๊ะ (finishing/destroyed = no-op) แต่ตัว dialog ถูก create ไว้แล้ว
 */
internal fun AlertDialog.showGuarded(on: Activity): Boolean =
    if (canShowDialog(on.isFinishing, on.isDestroyed)) { show(); true } else false
