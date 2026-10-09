package com.example.scheduleapp

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var scheduleView: ScheduleView
    private val handler = Handler(Looper.getMainLooper())
    private var infoPrefix = ""
    private var lastUpdateMs = 0L
    private var currentData: ScheduleData? = null
    private var currentChangeKeys: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部条：第一行 小标题 + 两个小圆角按钮，第二行 灰色信息
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

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.addView(scheduleView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // 先展示本地缓存（若有），再后台刷新
        val cached = getSharedPreferences("schedule", MODE_PRIVATE).getString("json", null)
        if (cached != null) {
            try {
                lastUpdateMs = getSharedPreferences("schedule", MODE_PRIVATE).getLong("last_update_ms", 0)
                val d = ScheduleApi.parse(cached)
                render(d)
                startTicker()
            } catch (e: Exception) {
                // 缓存损坏则忽略
            }
        }
        refresh()
    }

    /** 后台线程抓取，成功后回主线程渲染并写入缓存。 */
    private fun refresh() {
        statusView.text = "加载中…"
        val uuid = getUuid()
        Thread {
            try {
                val raw = ScheduleApi.fetchRaw(uuid)
                val data = ScheduleApi.parse(raw)
                currentChangeKeys = computeChanges(cachedData(), data)
                lastUpdateMs = System.currentTimeMillis()
                getSharedPreferences("schedule", MODE_PRIVATE)
                    .edit().putString("json", raw).putLong("last_update_ms", lastUpdateMs).apply()
                handler.post {
                    render(data)
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

    private fun render(data: ScheduleData) {
        currentData = data
        scheduleView.setData(data, todayDayOfWeek())
        applyDisplayStyle()
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        scheduleView.setChangeKeys(currentChangeKeys)
        val campus = if (data.campus.isNotEmpty()) " · ${data.campus}" else ""
        infoPrefix = "${data.schoolYear}学年第${data.semester}学期$campus · ${metaText(data)}"
        updateStatus()
    }

    private fun metaText(data: ScheduleData): String =
        "第${data.currentWeek}周/共${data.maxWeek}周"

    /** 顶部信息行：学期周次 + 距离上次更新。 */
    private fun updateStatus() {
        statusView.text = if (lastUpdateMs > 0)
            "$infoPrefix · 更新于 ${relTime(lastUpdateMs)}"
        else infoPrefix
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

    /** 课程标识：星期|开始节次|课名。 */
    private fun keyOf(lesson: Lesson): String = "${lesson.dayOfWeek}|${lesson.beginSection}|${lesson.course}"

    /** 从设置读取显示参数并应用到课表。 */
    private fun applyDisplayStyle() {
        val p = getSharedPreferences("schedule", MODE_PRIVATE)
        scheduleView.setDisplayStyle(
            p.getFloat("font_course", dp(12).toFloat()),
            p.getFloat("font_loc", dp(10).toFloat()),
            p.getFloat("font_header", dp(15).toFloat()),
            p.getFloat("header_h", dp(48).toFloat()),
            p.getFloat("cell_h", 0f),
            p.getFloat("name_y", 0.33f),
            p.getFloat("loc_y", 0.78f)
        )
    }

    override fun onResume() {
        super.onResume()
        if (currentData != null) applyDisplayStyle()
    }

    /** 读取上次抓取缓存（变更对比基准）。 */
    private fun cachedData(): ScheduleData? {
        val raw = getSharedPreferences("schedule", MODE_PRIVATE).getString("json", null) ?: return null
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
        getSharedPreferences("schedule", MODE_PRIVATE)
            .getStringSet("ignored_changes", emptySet())?.contains("$k@$loc") ?: false

    private fun ignoreChange(k: String, loc: String) {
        val set = getSharedPreferences("schedule", MODE_PRIVATE)
            .getStringSet("ignored_changes", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("$k@$loc")
        getSharedPreferences("schedule", MODE_PRIVATE)
            .edit().putStringSet("ignored_changes", set).apply()
    }

    /** 取消该课名所有节的强调（持久化）。 */
    private fun ignoreAllFor(lesson: Lesson) {
        val set = getSharedPreferences("schedule", MODE_PRIVATE)
            .getStringSet("ignored_changes", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentData?.lessons?.filter { it.course == lesson.course }?.forEach {
            set.add("${keyOf(it)}@${it.location}")
        }
        getSharedPreferences("schedule", MODE_PRIVATE)
            .edit().putStringSet("ignored_changes", set).apply()
    }

    private fun readStringMap(prefix: String): Map<String, String> =
        getSharedPreferences("schedule", MODE_PRIVATE).all.entries
            .mapNotNull { (k, v) ->
                if (k.startsWith(prefix) && v is String) k.removePrefix(prefix) to v as String else null
            }.toMap()

    private fun currentDisplayName(lesson: Lesson): String {
        val key = keyOf(lesson)
        val one = getSharedPreferences("schedule", MODE_PRIVATE).getString("oneName_$key", null)
        if (one != null) return one
        return getSharedPreferences("schedule", MODE_PRIVATE).getString("nameMap_${lesson.course}", null)
            ?: lesson.course
    }

    private fun currentDisplayLoc(lesson: Lesson): String {
        val key = keyOf(lesson)
        val one = getSharedPreferences("schedule", MODE_PRIVATE).getString("oneLoc_$key", null)
        if (one != null) return one
        return getSharedPreferences("schedule", MODE_PRIVATE).getString("locMap_${lesson.location}", null)
            ?: lesson.location
    }

    private fun saveDisplayRule(lesson: Lesson, nameText: String, locText: String, applyAll: Boolean) {
        val prefs = getSharedPreferences("schedule", MODE_PRIVATE)
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

    /** 设置变更后刷新渲染。 */
    private fun refreshDisplay() {
        val d = currentData ?: return
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        scheduleView.setChangeKeys(computeChanges(cachedData(), d))
        scheduleView.invalidate()
    }

    /** 点击课程块弹出非全屏悬浮窗。 */
    private fun showLessonDialog(lesson: Lesson) {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        // 标题
        root.addView(TextView(this).apply {
            text = currentDisplayName(lesson)
            textSize = 16f
            setTextColor(0xFF17365D.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        // 详情
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
        // 显示名输入
        root.addView(fieldLabel("课程显示名（留空恢复原样）"))
        val nameInput = fieldInput(currentDisplayName(lesson))
        root.addView(nameInput, fieldInputLp())
        // 位置显示输入
        root.addView(fieldLabel("位置显示（可只写部分，如 教2-A-205）"))
        val locInput = fieldInput(currentDisplayLoc(lesson))
        root.addView(locInput, fieldInputLp())
        // 作用范围
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAll = RadioButton(this).apply { text = "全部同名课" }
        val rbOne = RadioButton(this).apply { text = "仅这一节" }
        rg.addView(rbAll)
        rg.addView(rbOne)
        rbAll.isChecked = true
        root.addView(rg)
        // 按钮
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
        getSharedPreferences("schedule", MODE_PRIVATE)
            .getString("curriculum_uuid", ScheduleApi.DEFAULT_UUID)
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
