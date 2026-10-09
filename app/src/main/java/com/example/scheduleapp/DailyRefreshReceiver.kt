package com.example.scheduleapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 每天 8:00 触发的后台刷新：把 1~20 周课表全部更新到本地缓存。
 * 完成后重调度下一天 8:00。
 */
class DailyRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
                val uuid = prefs.getString("curriculum_uuid", ScheduleApi.DEFAULT_UUID)
                    ?: ScheduleApi.DEFAULT_UUID
                // 全量刷新 1~20 周
                for (w in 1..20) {
                    try {
                        val raw = ScheduleApi.fetchRaw(uuid, w)
                        prefs.edit().putString("weekCache_$w", raw).apply()
                    } catch (e: Exception) {
                        // 单周失败跳过，不影响其它周
                    }
                }
            } finally {
                pendingResult.finish()
                scheduleDaily(context)
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 202609

        /** 调度下一次每天 8:00 的后台刷新。 */
        fun scheduleDaily(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE,
                Intent(context, DailyRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val next = next8am()
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (e: SecurityException) {
                // 未授予精确闹钟权限时降级为宽松定时
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }

        private fun next8am(): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
    }
}
