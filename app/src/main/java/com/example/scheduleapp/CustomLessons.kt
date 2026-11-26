package com.example.scheduleapp

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户在空白节里手动添加的课程（只存本地，叠加在学校课表之上）。
 *
 * [weeks] 是生效周次集合：
 * - 空列表：整学期每周都显示；
 * - 非空：只在列出的那几周显示（支持单双周、指定几周混合）。
 *
 * 识别方式：合并成 Lesson 时把 weeks 写成 "#custom:<id>"，点开课程弹窗即可据此判断是自定义课程。
 */
data class CustomLesson(
    val id: Long,
    val course: String,
    val day: Int,          // 1=周一 … 7=周日
    val begin: Int,        // 起始节
    val length: Int,       // 节数
    val location: String,
    val teacher: String = "",
    val weeks: List<Int> = emptyList()
)

object CustomLessons {
    private const val KEY = "custom_lessons_json"
    /** Lesson.weeks 前缀，用于标记“这是本地手动添加的课程”。 */
    const val TAG = "#custom:"

    private val WEEK_NAMES = arrayOf("", "一", "二", "三", "四", "五", "六", "日")

    fun load(prefs: SharedPreferences): List<CustomLesson> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val out = ArrayList<CustomLesson>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0)
                val course = o.optString("course", "").trim()
                if (id <= 0 || course.isEmpty()) continue
                out.add(CustomLesson(
                    id = id,
                    course = course,
                    day = o.optInt("day", 1).coerceIn(1, 7),
                    begin = o.optInt("begin", 1).coerceAtLeast(1),
                    length = o.optInt("length", 1).coerceAtLeast(1),
                    location = o.optString("loc", ""),
                    teacher = o.optString("teacher", ""),
                    weeks = weeksOf(o)
                ))
            }
        } catch (e: Exception) { }
        return out
    }

    /** 读取周次：优先新字段 weeks；旧数据（all/week）自动转换。 */
    private fun weeksOf(o: JSONObject): List<Int> {
        val arr = o.optJSONArray("weeks")
        if (arr != null) {
            val list = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val w = arr.optInt(i, 0)
                if (w > 0) list.add(w)
            }
            return list
        }
        // 旧版本：all=true 表示每周，否则只有 week 那一周
        if (o.optBoolean("all", true)) return emptyList()
        val w = o.optInt("week", 0)
        return if (w > 0) listOf(w) else emptyList()
    }

    fun save(prefs: SharedPreferences, list: List<CustomLesson>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("course", c.course)
                put("day", c.day)
                put("begin", c.begin)
                put("length", c.length)
                put("loc", c.location)
                put("teacher", c.teacher)
                put("weeks", JSONArray(c.weeks.sorted()))
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 新增或按 id 更新一条；返回更新后的列表。 */
    fun upsert(prefs: SharedPreferences, c: CustomLesson): List<CustomLesson> {
        val list = load(prefs).toMutableList()
        val idx = list.indexOfFirst { it.id == c.id }
        if (idx >= 0) list[idx] = c else list.add(c)
        save(prefs, list)
        return list
    }

    fun remove(prefs: SharedPreferences, id: Long): List<CustomLesson> {
        val list = load(prefs).filter { it.id != id }
        save(prefs, list)
        return list
    }

    /** 这个 Lesson 是不是本地手动添加的课程。 */
    fun isCustom(lesson: Lesson): Boolean = lesson.weeks.startsWith(TAG)

    /** 取自定义课程 id；不是自定义课程返回 null。 */
    fun idOf(lesson: Lesson): Long? =
        if (isCustom(lesson)) lesson.weeks.removePrefix(TAG).toLongOrNull() else null

    /** 把该周命中的自定义课程合并进课表数据；没有则原样返回。 */
    fun merge(d: ScheduleData, week: Int, prefs: SharedPreferences): ScheduleData {
        val hit = load(prefs).filter { it.weeks.isEmpty() || it.weeks.contains(week) }
        if (hit.isEmpty()) return d
        val extra = hit.map { toLesson(d, it) }
        return d.copy(lessons = d.lessons + extra, lessonCount = d.lessonCount + extra.size)
    }

    /** 周次文案：空 = 每周；否则压缩成 “1-4,6,8-10周”。 */
    fun weeksText(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "每周（整学期）"
        val sorted = weeks.sorted()
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val w = sorted[i]
            if (w == prev) continue
            if (w == prev + 1) { prev = w; continue }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = w; prev = w
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",") + "周"
    }

    private fun toLesson(d: ScheduleData, c: CustomLesson): Lesson {
        val start = d.sectionTimes.getOrNull(c.begin - 1)?.second?.substringBefore("-") ?: ""
        val end = d.sectionTimes.getOrNull(c.begin + c.length - 1)?.second?.substringAfter("-") ?: ""
        val time = if (start.isNotBlank() && end.isNotBlank()) "$start-$end" else ""
        val secText = if (c.length == 1) "第${c.begin}节" else "${c.begin}-${c.begin + c.length - 1}节"
        return Lesson(
            course = c.course,
            dayOfWeek = c.day.coerceIn(1, 7),
            weekday = "周${WEEK_NAMES[c.day.coerceIn(1, 7)]}",
            beginSection = c.begin,
            length = c.length,
            sections = secText,
            time = time,
            location = c.location,
            teacher = c.teacher,
            weeks = TAG + c.id,
            date = ""
        )
    }
}
