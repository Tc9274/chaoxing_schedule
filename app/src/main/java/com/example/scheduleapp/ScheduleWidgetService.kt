package com.example.scheduleapp

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.Calendar

/** 课表小组件的课程数据源：从本地缓存读取当天课程。 */
class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        LessonFactory(applicationContext, intent)
}

/** 当天课程浅色池（与 ScheduleView 同款）。 */
private val PALETTE = intArrayOf(
    0xFFEF9A9A.toInt(), 0xFFFFCC80.toInt(), 0xFFD7CCC8.toInt(), 0xFFA5D6A7.toInt(),
    0xFF80CBC4.toInt(), 0xFF90CAF9.toInt(), 0xFF9FA8DA.toInt(), 0xFFCE93D8.toInt(),
    0xFFF48FB1.toInt(), 0xFFBCAAA4.toInt()
)

private class LessonFactory(
    private val ctx: Context,
    @Suppress("UNUSED_PARAMETER") intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var lessons: List<Lesson> = emptyList()

    override fun onCreate() = load()
    override fun onDataSetChanged() = load()

    private fun load() {
        val prefs = ctx.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        var realWeek = prefs.getInt("real_week", 0).takeIf { it > 0 } ?: 0
        if (realWeek <= 0) {
            val last = prefs.getInt("last_view_week", 0).coerceAtLeast(1)
            realWeek = last
            prefs.getString("weekCache_$last", null)?.let { raw ->
                realWeek = try { ScheduleApi.parse(raw).currentWeek.coerceAtLeast(1) } catch (e: Exception) { last }
            }
        }
        // 今天/明天（offset=0 今天，1 明天；周日看明天跨到下周）
        val offset = prefs.getInt("widget_view_offset", 0).coerceIn(0, 1)
        val todayDay = todayIndex()
        var targetDay = todayDay + offset
        var targetWeek = realWeek
        if (offset == 1 && todayDay == 7) {
            targetDay = 1
            targetWeek = realWeek + 1
        }
        var raw = prefs.getString("weekCache_$targetWeek", null)
        if (raw == null && targetWeek != realWeek) {
            raw = prefs.getString("weekCache_$realWeek", null)
        }
        val data = raw?.let { runCatching { ScheduleApi.parse(it) }.getOrNull() }
        lessons = data?.lessons
            ?.filter { it.dayOfWeek == targetDay }
            ?.sortedBy { it.beginSection }
            ?: emptyList()
    }

    private fun todayIndex(): Int = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7; else -> 0
    }

    override fun getCount(): Int = if (lessons.isEmpty()) 1 else lessons.size

    override fun getViewAt(pos: Int): RemoteViews {
        val prefs = ctx.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        // 点击打开 App
        val openPi = PendingIntentActivity(pos)
        if (lessons.isEmpty()) {
            val empty = RemoteViews(ctx.packageName, R.layout.widget_schedule_item)
            empty.setTextViewText(R.id.item_course, "今天没课")
            empty.setTextViewText(R.id.item_sub, "")
            empty.setInt(R.id.item_root, "setBackgroundColor", 0xFF26282B.toInt())
            empty.setTextColor(R.id.item_course, 0xFFB0B6BC.toInt())
            empty.setOnClickPendingIntent(R.id.item_root, openPi)
            return empty
        }
        val l = lessons[pos]
        val key = "${l.dayOfWeek}|${l.beginSection}|${l.course}"
        val name = prefs.getString("oneName_$key", null)
            ?: prefs.getString("nameMap_${l.course}", null)
            ?: l.course
        val loc = prefs.getString("oneLoc_$key", null)
            ?: prefs.getString("locMap_${l.location}", null)
            ?: l.location

        val rv = RemoteViews(ctx.packageName, R.layout.widget_schedule_item)
        rv.setTextViewText(R.id.item_course, name)
        val sub = if (l.time.isEmpty()) "${l.sections}节 · $loc" else "${l.sections}节 ${l.time} · $loc"
        rv.setTextViewText(R.id.item_sub, sub)
        rv.setInt(R.id.item_root, "setBackgroundColor", lessonColor(l, prefs))
        rv.setOnClickPendingIntent(R.id.item_root, openPi)
        return rv
    }

    private fun PendingIntentActivity(pos: Int): android.app.PendingIntent {
        // 与点击 App 图标一致：用 launcher intent，保证回本周等行为与图标进入相同
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java)
        return android.app.PendingIntent.getActivity(ctx, pos, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    private fun lessonColor(l: Lesson, prefs: SharedPreferences): Int {
        val key = "${l.dayOfWeek}|${l.beginSection}|${l.course}"
        prefs.getInt("oneColor_$key", 0).takeIf { it != 0 }?.let { return it }
        prefs.getInt("courseColor_${l.course}", 0).takeIf { it != 0 }?.let { return it }
        return PALETTE[(l.course.hashCode() and 0x7fffffff) % PALETTE.size]
    }

    override fun getViewTypeCount(): Int = 2
    override fun hasStableIds(): Boolean = true
    override fun getItemId(pos: Int): Long = pos.toLong()
    override fun getLoadingView(): RemoteViews? = null
    override fun onDestroy() {}
}
