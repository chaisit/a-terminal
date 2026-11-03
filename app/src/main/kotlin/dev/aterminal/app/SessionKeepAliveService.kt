package dev.aterminal.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * ไม่มี logic ของ session: แค่ทำให้ process นับเป็น foreground เพื่อให้ระบบไม่บล็อก network ตอนจอปิด/สลับแอป
 * (หลักฐาน API 35: `network blocked=true` 5 วินาทีหลัง onStop แล้ว socket โดน ECONNABORTED)
 * session ยังผูกกับ MainActivity เรียกทุกอย่างบน main thread
 *
 * ห้ามเรียก stopService จากนอก service: ถ้าหยุดก่อนถึง startForeground ระบบจะ crash แอป
 * ทางหยุดทางเดียวคือตั้ง desired แล้วให้ service อ่านเองใน onStartCommand หลัง startForeground แล้ว
 */
class SessionKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ต้องเรียกก่อนเสมอแม้จะกำลังจะหยุด เป็นสัญญาของ startForegroundService
        val first = !inForeground
        val state = desired
        startRequested = false
        promote(notification(state.text.ifEmpty { "Closing sessions…" }))
        if (first) ConnectionLog.d("service foreground run=${state.run}")
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            ConnectionLog.d("service disconnect all")
            onDisconnectAll?.invoke()
        }
        apply(desired)
        // ระบบฆ่าแล้วไม่ต้องเริ่มใหม่: process ตาย = session หายไปด้วยแล้ว
        return START_NOT_STICKY
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
        inForeground = true
    }

    private fun apply(d: KeepAlivePolicy.Decision) {
        if (!d.run) {
            setWakeLock(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
            return
        }
        setWakeLock(d.wakeLock)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(d.text))
    }

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aterminal:sessions")
                ?.apply { setReferenceCounted(false); acquire() }
            ConnectionLog.d("service wake lock acquired")
        } else if (!on && wakeLock != null) {
            wakeLock?.release()
            wakeLock = null
            ConnectionLog.d("service wake lock released")
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Active SSH sessions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while sessions are open, so they stay connected with the screen off"
                    setShowBadge(false)
                })
        }
        // launch intent เดียวกับ launcher: กลับ task เดิมโดยไม่สร้าง Activity ใหม่ (Activity ใหม่ = session ใหม่)
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, SessionKeepAliveService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("A-Terminal")
            .setContentText(text) // ห้ามใส่ host/user: แสดงบน lock screen ได้
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Disconnect all", disconnect).build())
            .build()
    }

    override fun onDestroy() {
        setWakeLock(false)
        if (instance === this) instance = null
        startRequested = false
        ConnectionLog.d("service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "dev.aterminal.app.DISCONNECT_ALL"

        private var instance: SessionKeepAliveService? = null
        private var desired = KeepAlivePolicy.Decision(false, false, "")
        private var startRequested = false
        var onDisconnectAll: (() -> Unit)? = null

        /** canStart = Activity อยู่ foreground: เริ่มจาก background ได้ exception จึงต้องรอ onStart รอบถัดไป */
        fun sync(ctx: Context, d: KeepAlivePolicy.Decision, canStart: Boolean) {
            desired = d
            val svc = instance
            when {
                svc != null && svc.inForeground -> svc.apply(d)
                d.run && canStart && !startRequested -> try {
                    startRequested = true
                    ctx.applicationContext.startForegroundService(Intent(ctx, SessionKeepAliveService::class.java))
                } catch (e: IllegalStateException) { // รวม ForegroundServiceStartNotAllowedException
                    startRequested = false
                    ConnectionLog.d("service start refused ${e.javaClass.name}")
                }
            }
        }
    }
}
