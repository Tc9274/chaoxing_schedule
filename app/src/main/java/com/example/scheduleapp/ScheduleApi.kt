package com.example.scheduleapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一节课 */
data class Lesson(
    val course: String,
    val dayOfWeek: Int,        // 1=周一 … 7=周日
    val weekday: String,
    val beginSection: Int,     // 起始节次
    val length: Int,           // 节数
    val sections: String,      // 如 "1-2节"
    val time: String,          // 如 "08:40-10:05"
    val location: String,
    val teacher: String,
    val weeks: String,
    val date: String
)

/** 学段 / 课程表整体 */
data class ScheduleData(
    val uuid: String,
    val schoolYear: String,
    val semester: String,
    val currentWeek: Int,
    val maxWeek: Int,
    val campus: String,
    val lessonCount: Int,
    val sectionTimes: List<Pair<Int, String>>, // (节次, "起-止")
    val lessons: List<Lesson>
)

/**
 * 抓取超星课表并解析。
 * 与电脑端 fetch_schedule.py 同源：调用超星 getOtherLessons 接口（无需登录）。
 */
object ScheduleApi {

    private const val API_BASE = "https://kb.chaoxing.com/curriculum/getOtherLessons"
    const val DEFAULT_UUID = ""

    /** 从链接或裸字符串中提取 curriculumUuid；提取不到返回 null。 */
    fun extractUuid(urlOrUuid: String): String? {
        val m = Regex("curriculumUuid=([0-9a-fA-F\\-]{8,})").find(urlOrUuid)
        if (m != null) return m.groupValues[1]
        val t = urlOrUuid.trim()
        return if (Regex("[0-9a-fA-F\\-]{8,}").matches(t)) t else null
    }

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val UA = ("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")

    /** 调用接口，返回原始 JSON 字符串。失败抛 IOException。 */
    fun fetchRaw(uuid: String): String {
        val url = URL("$API_BASE?curriculumUuid=$uuid&curTime=${System.currentTimeMillis()}")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer",
                "https://kb.chaoxing.com/res/pc/curriculum/schedule.html?curriculumUuid=$uuid")
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 把接口返回的 JSON 解析为 ScheduleData。 */
    fun parse(raw: String): ScheduleData {
        val root = JSONObject(raw)
        if (root.optInt("result") != 1) throw IOException("接口返回异常")
        val data = root.getJSONObject("data")
        val curriculum = data.optJSONObject("curriculum") ?: JSONObject()

        val uuid = curriculum.optString("uuid")
        val schoolYear = curriculum.optString("schoolYear")
        val semester = curriculum.optString("semester")
        val currentWeek = curriculum.optInt("realCurrentWeek", curriculum.optInt("currentWeek", 0))
        val maxWeek = curriculum.optInt("maxWeek", 0)

        // 校区时间配置：取课程所在校区（滨海），否则取第一个
        var campus = ""
        var timeConfig: JSONArray? = null
        val cfgArr = curriculum.optJSONArray("campusLessonTimeConfigArray")
        if (cfgArr != null && cfgArr.length() > 0) {
            for (i in 0 until cfgArr.length()) {
                val cfg = cfgArr.getJSONObject(i)
                val name = cfg.optString("name", "")
                if (name.contains("滨海")) {
                    campus = name
                    timeConfig = cfg.optJSONArray("timeConfig")
                    break
                }
            }
            if (timeConfig == null) {
                val first = cfgArr.getJSONObject(0)
                campus = first.optString("name", "")
                timeConfig = first.optJSONArray("timeConfig")
            }
        }

        val sectionTimes = mutableListOf<Pair<Int, String>>()
        if (timeConfig != null) {
            for (i in 0 until timeConfig.length()) {
                sectionTimes.add(Pair(i + 1, timeConfig.getString(i)))
            }
        }

        val lessons = mutableListOf<Lesson>()
        val arr = data.optJSONArray("lessonArray")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val le = arr.getJSONObject(i)
                val name = le.optString("name", "")
                if (name.isEmpty()) continue
                val begin = le.optInt("beginNumber", 1)
                val length = le.optInt("length", 1)
                val end = begin + length - 1
                val day = le.optInt("dayOfWeek", 0)

                val startTime = sectionTimes.firstOrNull { it.first == begin }?.second ?: ""
                val endTime = sectionTimes.firstOrNull { it.first == end }?.second ?: ""
                val startHm = if (startTime.contains("-")) startTime.substringBefore("-") else ""
                val endHm = if (endTime.contains("-")) endTime.substringAfter("-") else ""
                val time = if (startHm.isNotEmpty() && endHm.isNotEmpty()) "$startHm-$endHm" else ""

                lessons.add(Lesson(
                    course = name,
                    dayOfWeek = day,
                    weekday = if (day in 1..7) WEEKDAYS[day - 1] else "",
                    beginSection = begin,
                    length = length,
                    sections = if (length == 1) "$begin" else "${begin}-${end}节",
                    time = time,
                    location = le.optString("location", ""),
                    teacher = le.optString("teacherName", ""),
                    weeks = le.optString("weeks", ""),
                    date = le.optString("day", "")
                ))
            }
        }

        // 按 星期 → 起始节 排序
        lessons.sortWith(compareBy({ it.dayOfWeek }, { it.beginSection }))

        return ScheduleData(
            uuid = uuid, schoolYear = schoolYear, semester = semester,
            currentWeek = currentWeek, maxWeek = maxWeek, campus = campus,
            lessonCount = lessons.size, sectionTimes = sectionTimes,
            lessons = lessons
        )
    }
}
