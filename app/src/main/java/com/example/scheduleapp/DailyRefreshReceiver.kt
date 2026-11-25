package com.example.scheduleapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 每天 0 点（默认，可在设置里改）触发的后台刷新：课表 + 周次表全部更新到本地缓存。
 * 完成后重调度下一天同一时间。
 */
class DailyRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
                var ok = 0; var fail = 0
                // 登录教务系统一次，拉整学期并生成全部周的缓存
                try {
                    val (_, maxW) = JwxtApi.refreshAll(prefs)
                    ok = maxW
                } catch (e: Exception) {
                    fail = 1
                }
                prefs.edit()
                    .putLong("last_daily_run", System.currentTimeMillis())
                    .putInt("last_daily_ok", ok)
                    .putInt("last_daily_fail", fail)
                    .apply()
            } finally {
                pendingResult.finish()
                ScheduleWidgetProvider.updateAll(context)   // 同步刷新桌面组件
                // 通知前台的主界面立即重读显示（定时刷新完成）
                context.sendBroadcast(Intent(ACTION_DAILY_DONE).setPackage(context.packageName))
                scheduleDaily(context)
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 202609
        /** 定时刷新完成广播：主界面在前台时收到后立即重读当前周。 */
        const val ACTION_DAILY_DONE = "com.example.scheduleapp.ACTION_DAILY_DONE"

        /** 按用户设置调度下一次后台刷新；未启用或已禁用则只取消旧闹钟。 */
        fun scheduleDaily(context: Context) {
            val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            cancelDaily(context)
            if (!prefs.getBoolean("daily_refresh_enabled", true)) return
            val t = prefs.getString("daily_refresh_time", "00:00") ?: "00:00"
            val hh = t.substringBefore(':').toIntOrNull() ?: 0
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
