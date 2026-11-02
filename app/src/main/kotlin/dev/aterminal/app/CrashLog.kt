package dev.aterminal.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * เก็บเหตุที่แอปปิดตัวเองไว้ในเครื่อง แล้วให้ user คัดลอกเองตอนเปิดครั้งถัดไป — ไม่ส่งออกจากเครื่อง
 * เหตุผล: release ไม่มี adb บนเครื่องจริง และ stack ของ release อ่านได้ด้วย mapping.txt ของ version นั้นเท่านั้น
 * ครอบ 3 ทาง: uncaught Java, WebView renderer ตาย, และ exit reason จากระบบ (native crash/ANR, API 30+)
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 16_000

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // ห้ามกลืน: เขียนแล้วส่งต่อให้ระบบปิดแอปตามปกติ
            runCatching { record(app, "uncaught on ${t.name}\n${stack(e)}") }
            previous?.uncaughtException(t, e)
        }
    }

    fun record(ctx: Context, body: String) {
        // ต่อท้าย ไม่ทับ: เหตุเดียวอาจมาหลายทาง (renderer ตายก่อน แล้ว Java ตามมา) เก็บเหตุแรกไว้เมื่อเต็ม
        val f = File(ctx.filesDir, FILE)
        val old = if (f.isFile) f.readText() else ""
        f.writeText((old + body + "\n").take(MAX_CHARS))
    }

    /** report ที่ยังไม่เคยแสดง แล้วลบทิ้ง (แสดงครั้งเดียว) — null = ไม่มีอะไรผิดปกติ */
    fun consume(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        val body = if (f.isFile) f.readText() else null
        f.delete()
        val exit = lastAbnormalExit(ctx)
        if (body == null && exit == null) return null
        return format(ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?", Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, body, exit)
    }

    private fun lastAbnormalExit(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = ctx.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val latest = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() }.getOrNull()
            ?: return null
        if (latest.timestamp <= prefs.getLong("seen", 0L)) return null
        prefs.edit().putLong("seen", latest.timestamp).apply()
        val abnormal = latest.reason == ApplicationExitInfo.REASON_CRASH ||
            latest.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            latest.reason == ApplicationExitInfo.REASON_ANR ||
            latest.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
            latest.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            latest.reason == ApplicationExitInfo.REASON_SIGNALED
        if (!abnormal) return null
        return "reason=${latest.reason} status=${latest.status} description=${latest.description} at=${latest.timestamp}"
    }

    internal fun format(version: String, maker: String, model: String, sdk: Int, body: String?, exit: String?): String =
        buildString {
            append("A-Terminal ").append(version).append(" on ").append(maker).append(' ').append(model)
            append(" (API ").append(sdk).append(")\n")
            if (exit != null) append("exit: ").append(exit).append('\n')
            if (body != null) append(body).append('\n')
        }

    internal fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
