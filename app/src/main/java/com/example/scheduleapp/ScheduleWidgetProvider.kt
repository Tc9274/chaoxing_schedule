package com.example.scheduleapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小组件：显示当天/次日课程 + 刷新按钮 + 左右切换。
 * 刷新按钮触发联网重抓本周；左右箭头切换今天/明天；数据渲染由 ScheduleWidgetService 完成。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> refreshData(context)
            ACTION_PREV -> changeOffset(context, -1)
            ACTION_NEXT -> changeOffset(context, +1)
        }
    }

    private fun changeOffset(context: Context, delta: Int) {
        val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        val off = (prefs.getInt("widget_view_offset", 0) + delta).coerceIn(0, 1)
        prefs.edit().putInt("widget_view_offset", off).apply()
        updateAll(context)
    }

    /** 联网重抓本周并更新组件。 */
    private fun refreshData(context: Context) {
        val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        val uuid = prefs.getString("curriculum_uuid", "") ?: ""
        Thread {
            try {
                val week = currentRealWeek(prefs)
                val raw = ScheduleApi.fetchRaw(uuid, week)
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putString("weekCache_$week", raw)
                    .putLong("weekUpdate_$week", now)   // 与 App 同一刷新时间源
                    .apply()
            } catch (e: Exception) {
                // 抓取失败保留旧缓存
            }
            updateAll(context)
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "com.example.scheduleapp.ACTION_WIDGET_REFRESH"
        const val ACTION_PREV = "com.example.scheduleapp.ACTION_WIDGET_PREV"
        const val ACTION_NEXT = "com.example.scheduleapp.ACTION_WIDGET_NEXT"

        private fun currentRealWeek(prefs: SharedPreferences): Int {
            val last = prefs.getInt("last_view_week", 0).coerceAtLeast(1)
            val raw = prefs.getString("weekCache_$last", null)
            if (raw != null) {
                return try { ScheduleApi.parse(raw).currentWeek.coerceAtLeast(1) } catch (e: Exception) { last }
            }
            return last
        }

        /** 刷新所有已添加的组件。 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_schedule)

            // 与 App 统一的刷新时间（weekUpdate_ 同一来源，组件刷新/App 刷新都更新它）
            val week = currentRealWeek(prefs)
            val appMs = prefs.getLong("weekUpdate_$week", 0)
            views.setTextViewText(R.id.widget_update,
                if (appMs > 0) "更新 ${hm(appMs)}" else "")

            // 今天/明天
            val offset = prefs.getInt("widget_view_offset", 0).coerceIn(0, 1)
            views.setTextViewText(R.id.widget_day, dayLabel(offset))

            // 刷新按钮
            views.setOnClickPendingIntent(R.id.widget_refresh, pi(context, ACTION_REFRESH, 10))
            // 左右切换
            views.setOnClickPendingIntent(R.id.widget_prev, pi(context, ACTION_PREV, 11))
            views.setOnClickPendingIntent(R.id.widget_next, pi(context, ACTION_NEXT, 12))

            // 课程列表数据源
            val svc = Intent(context, ScheduleWidgetService::class.java)
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setRemoteAdapter(R.id.widget_list, svc)
            manager.updateAppWidget(id, views)
            // 通知列表数据变化，触发 RemoteViewsService 重新读取（切换/刷新后课表才会变）
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }

        private fun pi(context: Context, action: String, req: Int): PendingIntent =
            PendingIntent.getBroadcast(context, req,
                Intent(context, ScheduleWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun hm(ms: Long): String =
            SimpleDateFormat("HH:mm", Locale.CHINA).format(ms)

        /** offset=0 今天，offset=1 明天。 */
        private fun dayLabel(offset: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, offset)
            val wd = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "周一"; Calendar.TUESDAY -> "周二"; Calendar.WEDNESDAY -> "周三"
                Calendar.THURSDAY -> "周四"; Calendar.FRIDAY -> "周五"; Calendar.SATURDAY -> "周六"
                Calendar.SUNDAY -> "周日"; else -> ""
            }
            return (if (offset == 0) "今天 $wd" else "明天 $wd")
        }
    }
}
