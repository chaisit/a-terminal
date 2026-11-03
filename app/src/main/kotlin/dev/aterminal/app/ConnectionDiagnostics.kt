package dev.aterminal.app

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * เก็บสภาพแวดล้อมรอบ ๆ ตอน session หลุด ลง [ConnectionLog]: จอ, doze, network, เหตุที่ process ก่อนหน้าตาย
 * ไม่บันทึกชื่อ network หรือ address — network id เป็นเลขภายในเครื่อง
 */
class ConnectionDiagnostics(context: Context) {
    private val ctx = context.applicationContext
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    @Volatile private var stallThread: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            ConnectionLog.d("system ${intent.action?.substringAfterLast('.')} ${powerState()}")
        }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = ConnectionLog.d("network available id=$n")
        override fun onLost(n: Network) = ConnectionLog.d("network lost id=$n")
        // true = ระบบบล็อกแอปนี้ไม่ให้ใช้ network ทั้งที่ network ยังอยู่ (battery saver / OEM)
        override fun onBlockedStatusChanged(n: Network, blocked: Boolean) =
            ConnectionLog.d("network blocked=$blocked id=$n")
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            connectivity?.registerDefaultNetworkCallback(network)
        }.onFailure { ConnectionLog.d("diagnostics register failed ${it.javaClass.name}") }
        ConnectionLog.d("env sdk=${Build.VERSION.SDK_INT} model=${Build.MANUFACTURER}/${Build.MODEL} ${powerState()} ${restrictionState()}")
        logPreviousExits()
    }

    fun stop() {
        onForeground()
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { connectivity?.unregisterNetworkCallback(network) }
    }

    /**
     * ระหว่างอยู่ background: thread นี้นอน 1 วินาทีแล้วดูว่าตื่นช้าแค่ไหน
     * ตื่นช้าทั้งที่ uptime เดินต่อ = process ถูก freeze; ถ้า uptime ไม่เดินด้วย = เครื่องหลับ
     */
    fun onBackground() {
        if (stallThread != null) return
        stallThread = Thread {
            var up = SystemClock.uptimeMillis()
            var rt = SystemClock.elapsedRealtime()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val up2 = SystemClock.uptimeMillis()
                    val rt2 = SystemClock.elapsedRealtime()
                    if (rt2 - rt > STALL_MS) ConnectionLog.d("stall wall=${rt2 - rt}ms awake=${up2 - up}ms ${powerState()}")
                    up = up2
                    rt = rt2
                }
            } catch (_: InterruptedException) {
                // onForeground เรียกหยุด
            }
        }.apply { name = "aterm-stall"; isDaemon = true; start() }
    }

    fun onForeground() {
        stallThread?.interrupt()
        stallThread = null
    }

    private fun powerState(): String =
        "interactive=${power?.isInteractive} idle=${power?.isDeviceIdleMode} saver=${power?.isPowerSaveMode}"

    private fun restrictionState(): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val bucket = ctx.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
        val exempt = power?.isIgnoringBatteryOptimizations(ctx.packageName)
        return "bgRestricted=${am?.isBackgroundRestricted} bucket=$bucket batteryExempt=$exempt"
    }

    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).forEach { e ->
                ConnectionLog.d("prev-exit pid=${e.pid} reason=${e.reason} sub=${e.status} importance=${e.importance} at=${e.timestamp} desc=${e.description}")
            }
        }.onFailure { ConnectionLog.d("prev-exit failed ${it.javaClass.name}") }
    }

    private companion object { const val STALL_MS = 3000L }
}
