package com.example.scheduleapp

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var scheduleView: ScheduleView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }
    private var infoBase = ""
    private var viewWeek = 0
    private var lastShownWeek = 0
    private var maxWeek = 0
    private var lastUpdateMs = 0L
    private var currentData: ScheduleData? = null
    private var currentChangeKeys: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶部条
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(4))
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(this).apply {
            text = "课表"
            textSize = 17f
            setTextColor(0xFF17365D.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        val settingsBtn = pillButton("设置") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        val refreshBtn = pillButton("刷新") { refresh() }
        row1.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        slp.setMargins(0, 0, dp(8), 0)
        row1.addView(settingsBtn, slp)
        row1.addView(refreshBtn)
        header.addView(row1)
        statusView = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(statusView)

        scheduleView = ScheduleView(this)
        scheduleView.onLessonClick = { lesson -> showLessonDialog(lesson) }
        scheduleView.onWeekChanged = { week ->
            if (week != viewWeek) {
                viewWeek = week
                prefs.edit().putInt("last_view_week", week).apply()
                updateStatus()
                if (!showCachedWeek(week)) fetchWeek(week)
            }
        }

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(scheduleView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // 调度每天 8:00 后台刷新，并尝试申请精确闹钟权限
        DailyRefreshReceiver.scheduleDaily(this)
        ensureExactAlarmPermission()

        // 有周缓存则直接显示上次查看的周；否则首次全量获取 1~20 周
        val hasAny = prefs.all.keys.any { it.startsWith("weekCache_") }
        if (hasAny) {
            showCachedWeek(getCachedViewWeek())
            startTicker()
        } else {
            fetchAllWeeksInitial()
        }
    }

    /** 首次启动：依次拉取 1~20 周全部缓存。 */
    private fun fetchAllWeeksInitial() {
        statusView.text = "首次获取课表 0/20…"
        val uuid = getUuid()
        Thread {
            var realWeek = 0
            var ok = 0
            for (w in 1..20) {
                try {
                    val raw = ScheduleApi.fetchRaw(uuid, w)
                    prefs.edit().putString("weekCache_$w", raw).apply()
                    ok++
                    if (realWeek == 0) realWeek = ScheduleApi.parse(raw).currentWeek
                } catch (e: Exception) {
                    // 单周失败跳过
                }
                val p = ok
                handler.post { statusView.text = "首次获取课表 $p/20…" }
            }
            val week = realWeek.takeIf { it > 0 } ?: getCachedViewWeek()
            prefs.edit().putInt("last_view_week", week).apply()
            handler.post {
                showCachedWeek(week)
                startTicker()
                Toast.makeText(this, "首次课表获取完成", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** 手动刷新：只刷新当前正在看的这一周，并计算变更强调。 */
    private fun refresh() {
        val week = viewWeek.takeIf { it > 0 } ?: getCachedViewWeek()
        statusView.text = "刷新第${week}周…"
        val uuid = getUuid()
        val baseRaw = prefs.getString("base_json", null)
        Thread {
            try {
                val raw = ScheduleApi.fetchRaw(uuid, week)
                val data = ScheduleApi.parse(raw)
                val old = try { baseRaw?.let { ScheduleApi.parse(it) } } catch (e: Exception) { null }
                currentChangeKeys = computeChanges(old, data)
                prefs.edit()
                    .putString("weekCache_$week", raw)
                    .putString("base_json", raw)
                    .putInt("last_view_week", week)
                    .apply()
                lastUpdateMs = System.currentTimeMillis()
                viewWeek = week
                lastShownWeek = week
                handler.post {
                    renderWeek(data, week, true)
                    startTicker()
                    Toast.makeText(this, "更新成功（${data.lessonCount} 节）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    statusView.text = "加载失败，请检查网络"
                    Toast.makeText(this, "获取课表失败：${e.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 切周无缓存时：请求该周并缓存。 */
    private fun fetchWeek(week: Int) {
        statusView.text = "加载第${week}周…"
        val uuid = getUuid()
        Thread {
            try {
                val raw = ScheduleApi.fetchRaw(uuid, week)
                val data = ScheduleApi.parse(raw)
                prefs.edit().putString("weekCache_$week", raw).putInt("last_view_week", week).apply()
                handler.post { renderWeek(data, week, false) }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "获取第${week}周失败：${e.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
                    viewWeek = lastShownWeek
                    updateStatus()
                }
            }
        }.start()
    }

    /** 从缓存显示某周；有缓存并解析成功返回 true，否则 false。 */
    private fun showCachedWeek(week: Int): Boolean {
        val raw = prefs.getString("weekCache_$week", null) ?: return false
        val d = try { ScheduleApi.parse(raw) } catch (e: Exception) { null }
        if (d == null) return false
        renderWeek(d, week, false)
        return true
    }

    /** 渲染某周课表。emphasize=true 时应用变更强调（手动刷新）。 */
    private fun renderWeek(data: ScheduleData, week: Int, emphasize: Boolean) {
        currentData = data
        scheduleView.setData(data, todayDayOfWeek(), week)
        scheduleView.setAdjacentData(parseCached(week - 1), parseCached(week + 1))
        applyDisplayStyle()
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        scheduleView.setChangeKeys(if (emphasize) currentChangeKeys else emptySet())
        val campus = if (data.campus.isNotEmpty()) " · ${data.campus}" else ""
        infoBase = "${data.schoolYear}学年第${data.semester}学期$campus"
        viewWeek = week
        lastShownWeek = week
        maxWeek = data.maxWeek
        updateStatus()
    }

    private fun getCachedViewWeek(): Int {
        val saved = prefs.getInt("last_view_week", 0)
        if (saved > 0) return saved
        var max = 0
        for (k in prefs.all.keys) {
            if (k.startsWith("weekCache_")) {
                k.removePrefix("weekCache_").toIntOrNull()?.let { if (it > max) max = it }
            }
        }
        return max
    }

    /** 顶部信息行：学期 + 当前查看周 + 距离上次更新。 */
    private fun updateStatus() {
        val week = "第${viewWeek}周/共${maxWeek}周"
        statusView.text = if (lastUpdateMs > 0)
            "$infoBase · $week · 更新于 ${relTime(lastUpdateMs)}"
        else "$infoBase · $week"
    }

    /** 相对时间：刚刚 / N分钟前 / N小时前 / 具体时间。 */
    private fun relTime(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000}小时前"
            else -> {
                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                df.format(java.util.Date(ms))
            }
        }
    }

    /** 每 30 秒刷新一次顶部时间显示。 */
    private fun startTicker() {
        handler.removeCallbacks(tickerRunnable)
        handler.postDelayed(tickerRunnable, 30_000)
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    /** 申请精确闹钟权限（Android 12+，用于每天 8:00 准点刷新）。 */
    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        if (am.canScheduleExactAlarms()) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        } catch (e: Exception) {
            // 用户可能已拒绝或系统不支持，忽略
        }
    }

    /** 课程标识：星期|开始节次|课名。 */
    private fun keyOf(lesson: Lesson): String = "${lesson.dayOfWeek}|${lesson.beginSection}|${lesson.course}"

    /** 从设置读取显示参数并应用到课表。字号/表头高为 0 时用自动默认。 */
    private fun applyDisplayStyle() {
        val fc = prefs.getFloat("font_course", 0f)
        val fl = prefs.getFloat("font_loc", 0f)
        val fh = prefs.getFloat("font_header", 0f)
        val hh = prefs.getFloat("header_h", 0f)
        scheduleView.setDisplayStyle(
            if (fc > 0) fc else dp(12).toFloat(),
            if (fl > 0) fl else dp(10).toFloat(),
            if (fh > 0) fh else dp(15).toFloat(),
            if (hh > 0) hh else dp(48).toFloat(),
            prefs.getFloat("cell_h", 0f),
            prefs.getFloat("name_y", 0.33f),
            prefs.getFloat("loc_y", 0.78f)
        )
        scheduleView.setAnimDurations(
            prefs.getInt("anim_switch", 300),
            prefs.getInt("anim_back", 300),
            prefs.getInt("anim_cancel", 300)
        )
    }

    override fun onResume() {
        super.onResume()
        if (currentData != null) applyDisplayStyle()
    }

    /** 读取某周的缓存数据（供相邻屏滑动显示）。 */
    private fun parseCached(week: Int): ScheduleData? {
        if (week < 1) return null
        val raw = prefs.getString("weekCache_$week", null) ?: return null
        return try { ScheduleApi.parse(raw) } catch (e: Exception) { null }
    }

    /** 变更对比基准（上次手动刷新保存的当前周）。 */
    private fun cachedData(): ScheduleData? {
        val raw = prefs.getString("base_json", null) ?: return null
        return try { ScheduleApi.parse(raw) } catch (e: Exception) { null }
    }

    /** 计算变更（位置变/调课/新增），过滤已忽略的。 */
    private fun computeChanges(oldD: ScheduleData?, newD: ScheduleData): Set<String> {
        if (oldD == null) return emptySet()
        val res = HashSet<String>()
        val newMap = newD.lessons.groupBy { keyOf(it) }
        val oldMap = oldD.lessons.groupBy { keyOf(it) }
        for ((k, list) in newMap) {
            val n = list.first()
            val o = oldMap[k]?.first()
            val changed = (o == null) || (o.location != n.location)
            if (changed && !isIgnored(k, n.location)) res.add(k)
        }
        return res
    }

    private fun isIgnored(k: String, loc: String): Boolean =
        prefs.getStringSet("ignored_changes", emptySet())?.contains("$k@$loc") ?: false

    private fun ignoreChange(k: String, loc: String) {
        val set = prefs.getStringSet("ignored_changes", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("$k@$loc")
        prefs.edit().putStringSet("ignored_changes", set).apply()
    }

    /** 取消该课名所有节的强调（持久化）。 */
    private fun ignoreAllFor(lesson: Lesson) {
        val set = prefs.getStringSet("ignored_changes", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentData?.lessons?.filter { it.course == lesson.course }?.forEach {
            set.add("${keyOf(it)}@${it.location}")
        }
        prefs.edit().putStringSet("ignored_changes", set).apply()
    }

    private fun readStringMap(prefix: String): Map<String, String> =
        prefs.all.entries.mapNotNull { (k, v) ->
            if (k.startsWith(prefix) && v is String) k.removePrefix(prefix) to v as String else null
        }.toMap()

    private fun currentDisplayName(lesson: Lesson): String {
        val key = keyOf(lesson)
        val one = prefs.getString("oneName_$key", null)
        if (one != null) return one
        return prefs.getString("nameMap_${lesson.course}", null) ?: lesson.course
    }

    private fun currentDisplayLoc(lesson: Lesson): String {
        val key = keyOf(lesson)
        val one = prefs.getString("oneLoc_$key", null)
        if (one != null) return one
        return prefs.getString("locMap_${lesson.location}", null) ?: lesson.location
    }

    private fun saveDisplayRule(lesson: Lesson, nameText: String, locText: String, applyAll: Boolean) {
        val key = keyOf(lesson)
        val ed = prefs.edit()
        if (applyAll) {
            if (nameText.isEmpty() || nameText == lesson.course) ed.remove("nameMap_${lesson.course}")
            else ed.putString("nameMap_${lesson.course}", nameText)
            if (locText.isEmpty() || locText == lesson.location) ed.remove("locMap_${lesson.location}")
            else ed.putString("locMap_${lesson.location}", locText)
        } else {
            if (nameText.isEmpty() || nameText == lesson.course) ed.remove("oneName_$key")
            else ed.putString("oneName_$key", nameText)
            if (locText.isEmpty() || locText == lesson.location) ed.remove("oneLoc_$key")
            else ed.putString("oneLoc_$key", locText)
        }
        ed.apply()
    }

    /** 显示名/强调设置变更后刷新渲染。 */
    private fun refreshDisplay() {
        val d = currentData ?: return
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        scheduleView.setChangeKeys(currentChangeKeys)
        scheduleView.invalidate()
    }

    /** 点击课程块弹出非全屏悬浮窗。 */
    private fun showLessonDialog(lesson: Lesson) {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = currentDisplayName(lesson)
            textSize = 16f
            setTextColor(0xFF17365D.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        val detail = "星期${lesson.dayOfWeek}  第${lesson.beginSection}~${lesson.beginSection + lesson.length - 1}节\n" +
                "位置：${lesson.location}\n老师：${lesson.teacher.ifEmpty { "—" }}\n周次：${lesson.weeks}"
        root.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6), 0, dp(12))
        })
        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(fieldLabel("课程显示名（留空恢复原样）"))
        val nameInput = fieldInput(currentDisplayName(lesson))
        root.addView(nameInput, fieldInputLp())
        root.addView(fieldLabel("位置显示（可只写部分，如 教2-A-205）"))
        val locInput = fieldInput(currentDisplayLoc(lesson))
        root.addView(locInput, fieldInputLp())
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAll = RadioButton(this).apply { text = "全部同名课" }
        val rbOne = RadioButton(this).apply { text = "仅这一节" }
        rg.addView(rbAll)
        rg.addView(rbOne)
        rbAll.isChecked = true
        root.addView(rg)
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnLp.setMargins(0, dp(10), 0, 0)
        root.addView(colorButton("保存显示设置", 0xFF3B6EB5.toInt()) {
            saveDisplayRule(lesson, nameInput.text.toString().trim(),
                locInput.text.toString().trim(), rbAll.isChecked)
            refreshDisplay()
            dlg.dismiss()
        }, btnLp)
        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(colorButton("取消本节强调", 0xFFFF9800.toInt()) {
            ignoreChange(keyOf(lesson), lesson.location)
            refreshDisplay()
            dlg.dismiss()
        }, btnLp)
        root.addView(colorButton("取消全部强调", 0xFFE64A19.toInt()) {
            ignoreAllFor(lesson)
            refreshDisplay()
            dlg.dismiss()
        }, btnLp)
        dlg.setContentView(root)
        dlg.show()
    }

    private fun divider(): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFE0E0E0.toInt())
        }
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF888888.toInt())
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun fieldInput(text: String): EditText = EditText(this).apply {
        setText(text)
        textSize = 14f
        setTextColor(0xFF222222.toInt())
    }

    private fun fieldInputLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(4))
        }

    private fun colorButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            setOnClickListener { onClick() }
        }

    /** 读取用户填写的课表 uuid；未填写则用默认课表。 */
    private fun getUuid(): String =
        prefs.getString("curriculum_uuid", ScheduleApi.DEFAULT_UUID)
            ?: ScheduleApi.DEFAULT_UUID

    /** 小圆角按钮。 */
    private fun pillButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3B6EB5.toInt())
            }
            setOnClickListener { onClick() }
        }

    /** 今天星期：1=周一 … 7=周日。 */
    private fun todayDayOfWeek(): Int {
        val cal = Calendar.getInstance()
        val calDay = cal.get(Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        return ((calDay + 5) % 7) + 1
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
