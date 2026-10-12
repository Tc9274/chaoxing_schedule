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
                var ok = 0; var fail = 0
                // 全量刷新 1~20 周，并同步更新各周"刷新时间"标记
                for (w in 1..20) {
                    try {
                        val raw = ScheduleApi.fetchRaw(uuid, w)
                        val wk = try { ScheduleApi.parse(raw).currentWeek } catch (e: Exception) { 0 }
                        val ed = prefs.edit().putString("weekCache_$w", raw)
                            .putLong("weekUpdate_$w", System.currentTimeMillis())
                        if (wk > 0) ed.putInt("real_week", wk)   // 同步确认真实本周
                        ed.apply()
                        ok++
                    } catch (e: Exception) {
                        fail++   // 单周失败跳过，不影响其它周
                    }
                }
                prefs.edit()
                    .putLong("last_daily_run", System.currentTimeMillis())
                    .putInt("last_daily_ok", ok)
                    .putInt("last_daily_fail", fail)
                    .apply()
            } finally {
                pendingResult.finish()
                ScheduleWidgetProvider.updateAll(context)   // 同步刷新桌面组件
                scheduleDaily(context)
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 202609

        /** 按用户设置调度下一次后台刷新；未启用或已禁用则只取消旧闹钟。 */
        fun scheduleDaily(context: Context) {
            val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            cancelDaily(context)
            if (!prefs.getBoolean("daily_refresh_enabled", true)) return
            val t = prefs.getString("daily_refresh_time", "08:00") ?: "08:00"
            val hh = t.substringBefore(':').toIntOrNull() ?: 8
            val mm = t.substringAfter(':').toIntOrNull() ?: 0
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE,
                Intent(context, DailyRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val next = nextAt(hh, mm)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (e: SecurityException) {
                // 未授予精确闹钟权限时降级为宽松定时
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }

        /** 取消已注册的后台刷新闹钟。 */
        fun cancelDaily(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE,
                Intent(context, DailyRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
        }

        private fun nextAt(hour: Int, minute: Int): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
    }
}
