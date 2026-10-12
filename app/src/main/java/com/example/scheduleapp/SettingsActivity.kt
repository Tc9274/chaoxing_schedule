package com.example.scheduleapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 设置界面：① 课表链接；② 显示设置（字号/排版），内嵌实时预览。
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }
    private val preview by lazy { ScheduleView(this) }
    private var previewData: ScheduleData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureDefaults()

        val scroll = SettingScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 标题
        root.addView(TextView(this).apply {
            text = "课表设置"
            textSize = 20f
            setTextColor(Color.parseColor("#17365D"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        // 项目主页链接（置顶，可点击）
        root.addView(TextView(this).apply {
            val url = "https://github.com/Tc9274/chaoxing_schedule"
            val sp = android.text.SpannableString("项目主页：$url")
            val us = sp.length - url.length
            sp.setSpan(android.text.style.URLSpan(url), us, us + url.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#3B6EB5")),
                us, us + url.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
            textSize = 13f
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(0, 0, 0, dp(10))
        })

        // ===== 课表链接 =====
        root.addView(sectionTitle("课表链接"))
        root.addView(TextView(this).apply {
            text = "粘贴你自己的超星课表链接，或直接粘贴 curriculumUuid。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(10))
        })
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "粘贴超星课表链接或 UUID"
            textSize = 14f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#999999"))
            gravity = Gravity.TOP
            setSingleLine(false)
            minLines = 1
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        input.setText(prefs.getString("curriculum_uuid", "") ?: "")
        val clearBtn = TextView(this).apply {
            text = "×"
            textSize = 22f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setOnClickListener {
                input.setText("")
                Toast.makeText(this@SettingsActivity, "已清空，点击下方保存链接生效", Toast.LENGTH_SHORT).show()
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(clearBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(inputRow, lpWithMargin(0, 0, 0, dp(6)))
        root.addView(Button(this).apply {
            text = "保存链接"
            setOnClickListener {
                val uuid = ScheduleApi.extractUuid(input.text.toString().trim())
                if (uuid == null) {
                    Toast.makeText(this@SettingsActivity,
                        "链接无效，请检查是否包含 curriculumUuid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("curriculum_uuid", uuid).apply()
                Toast.makeText(this@SettingsActivity, "已保存链接，返回主界面刷新即可", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, lpWithMargin(0, 0, 0, dp(8)))

        // ===== 显示设置 =====
        root.addView(spacer())
        root.addView(sectionTitle("显示设置"))
        root.addView(TextView(this).apply {
            text = "拖动滑块实时生效，下方预览可看到效果。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(8))
        })
        // 效果预览卡片：高度=屏幕2/5、两边留缝隙、圆角边框，内部可上下滚动浏览全部课表
        val previewCard = PreviewScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels * 2 / 5).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            clipToOutline = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        previewCard.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(previewCard)
        // 外层设置页滚动：按在预览卡片内时放行，交给预览自己滚动
        scroll.previewTarget = previewCard
        // 效果预览数据源：优先用当前查看周的缓存（weekCache_*），兼容旧 json key
        val raw = run {
            val w = prefs.getInt("last_view_week", 0).coerceAtLeast(1)
            var r = prefs.getString("weekCache_$w", null)
            if (r == null) {
                prefs.all.keys.firstOrNull { it.startsWith("weekCache_") }
                    ?.let { r = prefs.getString(it, null) }
            }
            r ?: prefs.getString("json", null)
        }
        if (raw != null) {
            try {
                previewData = ScheduleApi.parse(raw)
                preview.setData(previewData!!, todayDayOfWeek())
                refreshPreview()
            } catch (e: Exception) {
                // 无缓存数据则预览留空
            }
        }

        root.addView(sliderRow("font_course", "课程名字号", 0..15,
            initValue = prefs.getFloat("font_course", dp(11).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_course",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 11))

        root.addView(sliderRow("font_loc", "位置字号", 0..15,
            initValue = prefs.getFloat("font_loc", dp(10).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_loc",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 10))

        root.addView(sliderRow("font_header", "表头字号", 0..15,
            initValue = prefs.getFloat("font_header", dp(11).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_header",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 11))

        root.addView(sliderRow("header_h", "表头高度", 0..60,
            initValue = prefs.getFloat("header_h", dp(39).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("header_h",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 39))

        root.addView(sliderRow("cell_h", "格子高度", 0..50,
            initValue = prefs.getFloat("cell_h", 0f) / density,
            text = { n -> if (n == 0) "自动适配" else "${n} dp（超高需上下滚动）" },
            save = { n -> prefs.edit().putFloat("cell_h",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 48))

        root.addView(sliderRow("time_w", "节次宽度", 40..120,
            initValue = prefs.getFloat("time_w", dp(50).toFloat()) / density,
            text = { n -> "${n} dp" },
            save = { n -> prefs.edit().putFloat("time_w",
                dp(n).toFloat()).apply(); refreshPreview() },
            defaultVal = 50))
        root.addView(sliderRow("name_y", "课程名上下位置", 0..1000,
            initValue = prefs.getFloat("name_y", 0.27f) * 1000,
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("name_y", n / 1000f).apply(); refreshPreview() },
            defaultVal = 270))

        root.addView(sliderRow("loc_y", "位置上下位置", 0..1000,
            initValue = prefs.getFloat("loc_y", 0.60f) * 1000,
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("loc_y", n / 1000f).apply(); refreshPreview() },
            defaultVal = 600))

        // ===== 显示选项 =====
        root.addView(switchRow("时间分两行显示", prefs.getBoolean("time_two_line", true)) { on ->
            prefs.edit().putBoolean("time_two_line", on).apply()
            refreshPreview()
        })
        // 是否显示周六、周日（关闭后该周周末有课时周五右侧画绿线提示）
        root.addView(switchRow("显示周六", prefs.getBoolean("show_sat", true)) { on ->
            prefs.edit().putBoolean("show_sat", on).apply()
            refreshPreview()
        })
        root.addView(switchRow("显示周日", prefs.getBoolean("show_sun", true)) { on ->
            prefs.edit().putBoolean("show_sun", on).apply()
            refreshPreview()
        })
        root.addView(textColorRow())

        // ===== 动画速度 =====
        root.addView(spacer())
        root.addView(sectionTitle("动画速度"))
        root.addView(TextView(this).apply {
            text = "左右滑动切换周的动画时长（毫秒），调小更快、调大更慢。拖动滑块松手后，下方按所选时长实播一次对应动画。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(8))
        })
        val animPreview = AnimPreviewView(this)
        animPreview.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        root.addView(animPreview)
        root.addView(sliderRow("anim_switch", "切周动画", 0..1000,
            initValue = prefs.getInt("anim_switch", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_switch", n).apply() },
            defaultVal = 300))
        root.addView(sliderRow("anim_back", "弹回动画", 0..1000,
            initValue = prefs.getInt("anim_back", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_back", n).apply() },
            defaultVal = 300))
        root.addView(sliderRow("anim_cancel", "取消回弹", 0..1000,
            initValue = prefs.getInt("anim_cancel", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_cancel", n).apply() },
            defaultVal = 300))
        // 预览区支持手指左右滑动切换周；不自动播放。

        root.addView(spacer())
        root.addView(Button(this).apply {
            text = "恢复默认显示"
            setOnClickListener {
                // 同时清空所有课程自定义颜色与"仅本节"颜色（回到各课程默认自动色）
                prefs.all.keys.filter {
                    it.startsWith("courseColor_") || it.startsWith("oneColor_")
                }.forEach { prefs.edit().remove(it).apply() }
                prefs.edit()
                    .putFloat("font_course", dp(11).toFloat())
                    .putFloat("font_loc", dp(10).toFloat())
                    .putFloat("font_header", dp(11).toFloat())
                    .putFloat("header_h", dp(39).toFloat())
                    .putFloat("cell_h", dp(48).toFloat())
                    .putFloat("time_w", dp(50).toFloat())
                    .putFloat("name_y", 0.27f)
                    .putFloat("loc_y", 0.60f)
                    .putBoolean("show_sat", true)
                    .putBoolean("show_sun", true)
                    .putBoolean("time_two_line", true)
                    .putInt("text_color", 1)
                    .putInt("anim_switch", 300)
                    .putInt("anim_back", 300)
                    .putInt("anim_cancel", 300)
                    .apply()
                recreate()
                Toast.makeText(this@SettingsActivity, "已恢复默认显示设置", Toast.LENGTH_SHORT).show()
            }
        })

        // ===== 定时更新 =====
        root.addView(spacer())
        val dailyTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dailyTitleRow.addView(sectionTitle("定时更新"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dailyTitleRow.addView(TextView(this).apply {
            text = "全部更新"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(Color.parseColor("#2C7BB6"))
            setOnClickListener {
                // 手动全量刷新：复用定时更新的抓取逻辑（1-20 周 + 同步桌面组件）
                sendBroadcast(android.content.Intent(this@SettingsActivity, DailyRefreshReceiver::class.java))
                Toast.makeText(this@SettingsActivity, "正在全量更新 1-20 周…", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(dailyTitleRow)
        root.addView(TextView(this).apply {
            text = "每天自动后台刷新课表（全量更新本周及后续周，含桌面组件同步）。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(android.widget.Switch(this).apply {
            text = "启用每日自动刷新"
            textSize = 14f
            isChecked = prefs.getBoolean("daily_refresh_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("daily_refresh_enabled", checked).apply()
                DailyRefreshReceiver.scheduleDaily(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity,
                    if (checked) "已启用，将按下方时间自动刷新" else "已关闭每日自动刷新",
                    Toast.LENGTH_SHORT).show()
            }
        }, lpWithMargin(0, 0, 0, dp(8)))
        val timeText = TextView(this).apply {
            text = "每天自动刷新时间：${prefs.getString("daily_refresh_time", "08:00") ?: "08:00"}"
            textSize = 14f
            setTextColor(Color.parseColor("#17365D"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(timeText, lpWithMargin(0, 0, 0, dp(6)))
        timeText.setOnClickListener {
            val cur = prefs.getString("daily_refresh_time", "08:00") ?: "08:00"
            val h0 = cur.substringBefore(':').toIntOrNull() ?: 8
            val m0 = cur.substringAfter(':').toIntOrNull() ?: 0
            android.app.TimePickerDialog(this@SettingsActivity, { _, h, m ->
                val t = String.format("%02d:%02d", h, m)
                prefs.edit().putString("daily_refresh_time", t).apply()
                timeText.text = "每天自动刷新时间：$t"
                DailyRefreshReceiver.scheduleDaily(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "已设置，将在每天 $t 自动刷新", Toast.LENGTH_SHORT).show()
            }, h0, m0, true).show()
        }
        root.addView(TextView(this).apply {
            text = lastDailyText()
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(2))
        })

        setContentView(scroll)
    }

    /** 上次自动刷新状态的显示文案。 */
    private fun lastDailyText(): String {
        val run = prefs.getLong("last_daily_run", 0L)
        if (run <= 0) return "尚未自动刷新过"
        val ok = prefs.getInt("last_daily_ok", 0)
        val fail = prefs.getInt("last_daily_fail", 0)
        val c = java.util.Calendar.getInstance().apply { timeInMillis = run }
        val hm = String.format("%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        return "上次自动刷新：$hm · 成功 $ok 周 · 失败 $fail 周"
    }

    /** 滑块变化后刷新预览显示。 */
    private fun refreshPreview() {
        preview.setDisplayStyle(
            prefs.getFloat("font_course", dp(11).toFloat()),
            prefs.getFloat("font_loc", dp(10).toFloat()),
            prefs.getFloat("font_header", dp(11).toFloat()),
            prefs.getFloat("header_h", dp(39).toFloat()),
            prefs.getFloat("cell_h", 0f),
            prefs.getFloat("name_y", 0.27f),
            prefs.getFloat("loc_y", 0.60f)
        )
        preview.setShowDays(
            prefs.getBoolean("show_sat", true),
            prefs.getBoolean("show_sun", true))
        preview.setTimeWidth(prefs.getFloat("time_w", dp(58).toFloat()))
        preview.setTimeTwoLine(prefs.getBoolean("time_two_line", true))
        preview.setTextColor(when (prefs.getInt("text_color", 1)) {
            0 -> 0xFF000000.toInt()
            2 -> 0xFF9E9E9E.toInt()
            else -> 0xFFFFFFFF.toInt()
        })
        updatePreviewHeight()
        preview.invalidate()
    }

    /** 预览高度：始终超过预览卡片（屏幕2/5），保证内部可上下滚动浏览全部课表。 */
    private fun updatePreviewHeight() {
        val pd = previewData ?: return
        val sections = pd.sectionTimes.size.coerceAtLeast(1)
        val cellH = prefs.getFloat("cell_h", 0f)
        val header = prefs.getFloat("header_h", dp(48).toFloat())
        val cardH = resources.displayMetrics.heightPixels * 2 / 5
        val contentH = if (cellH > 0f) {
            (header + sections * cellH).toInt()
        } else {
            // 自动：每节约 56dp 铺开全部课表
            (header + sections * dp(56)).toInt()
        }
        // 强制超出卡片高度，确保可滚动浏览全部
        val h = maxOf(contentH, cardH + dp(20))
        preview.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h)
        preview.requestLayout()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#17365D"))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun spacer(): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFE0E0E0.toInt())
        }
    }.also { it.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(8), 0, dp(8)) } }

    /** 一个滑块行：标签 + SeekBar，拖动即保存并刷新预览。initValue 为 range 内的显示值。onStop 在松手时回调（用于播放动画预览）。 */
    private fun sliderRow(
        prefKey: String, title: String, range: IntRange,
        initValue: Float, text: (Int) -> String, save: (Int) -> Unit,
        onStop: ((Int) -> Unit)? = null, defaultVal: Int? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, dp(8), 0, 0)
        }
        fun refresh(progress: Int) {
            label.text = "$title：${text(progress)}" +
                    if (progress == defaultVal) "（默认）" else ""
        }
        val bar = SeekBar(this).apply {
            max = range.last - range.first
            progress = (initValue - range.first).roundToInt().coerceIn(0, max)
        }
        refresh(bar.progress + range.first)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val v = progress + range.first
                    save(v)
                    refresh(v)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val v = (seekBar?.progress ?: 0) + range.first
                onStop?.invoke(v)
            }
        })
        row.addView(label)
        row.addView(bar)
        return row
    }

    /** 开关行：左侧文字 + 右侧 Switch。 */
    private fun switchRow(labelText: String, init: Boolean, on: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(Color.parseColor("#222222"))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = Switch(this@SettingsActivity).apply {
                isChecked = init
                setOnCheckedChangeListener { _, ch -> on(ch) }
            }
            addView(sw, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    /** 文字颜色选择：黑/白/灰。 */
    private fun textColorRow(): LinearLayout {
        val choices = listOf("黑" to 0, "白" to 1, "灰" to 2)
        val chips = mutableListOf<TextView>()
        fun paintChips() {
            val cur = prefs.getInt("text_color", 1)
            for (c in chips) {
                val sel = c.tag as Int == cur
                c.setBackgroundColor(if (sel) Color.parseColor("#2C7BB6") else Color.parseColor("#EEEEEE"))
                c.setTextColor(if (sel) Color.WHITE else Color.parseColor("#222222"))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "文字颜色"
                textSize = 14f
                setTextColor(Color.parseColor("#222222"))
            })
            val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            choices.forEach { (name, v) ->
                val c = TextView(this@SettingsActivity).apply {
                    text = name
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    tag = v
                    setOnClickListener {
                        prefs.edit().putInt("text_color", tag as Int).apply()
                        refreshPreview()
                        paintChips()
                    }
                }
                chips.add(c)
                row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 0, dp(4), 0) })
            }
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) })
            paintChips()
        }
    }

    /** 首次进入设置时，把缺失的显示参数写入默认值（字号11/10/11、表高39、格高48、节次宽50、位置27%/60%）。 */
    private fun ensureDefaults() {
        val e = prefs.edit()
        if (!prefs.contains("font_course")) e.putFloat("font_course", dp(11).toFloat())
        if (!prefs.contains("font_loc")) e.putFloat("font_loc", dp(10).toFloat())
        if (!prefs.contains("font_header")) e.putFloat("font_header", dp(11).toFloat())
        if (!prefs.contains("header_h")) e.putFloat("header_h", dp(39).toFloat())
        if (!prefs.contains("cell_h")) e.putFloat("cell_h", dp(48).toFloat())
        if (!prefs.contains("time_w")) e.putFloat("time_w", dp(50).toFloat())
        if (!prefs.contains("name_y")) e.putFloat("name_y", 0.27f)
        if (!prefs.contains("loc_y")) e.putFloat("loc_y", 0.60f)
        if (!prefs.contains("show_sat")) e.putBoolean("show_sat", true)
        if (!prefs.contains("show_sun")) e.putBoolean("show_sun", true)
        if (!prefs.contains("time_two_line")) e.putBoolean("time_two_line", true)
        if (!prefs.contains("text_color")) e.putInt("text_color", 1)
        e.apply()
    }

    private fun lpWithMargin(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(l, t, r, b)
        }

    /** 平缓的惯性滚动：速度封顶后减小，长滑平滑减速不卡顿；快速短滑也不会一下冲到顶/底。 */
    /** 外层设置页滚动：手指按在预览卡片区域内时不拦截，交给预览内部滚动；预览外正常滚动页面。 */
    private inner class SettingScrollView(ctx: android.content.Context) : ScrollView(ctx) {
        var previewTarget: View? = null
        private var touchingPreview = false

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            val t = previewTarget
            if (t != null && t.visibility == View.VISIBLE) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        touchingPreview = pointInView(ev, t)
                        if (touchingPreview) return false
                    }
                    android.view.MotionEvent.ACTION_MOVE ->
                        if (touchingPreview) return false
                    else -> touchingPreview = false
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        private fun pointInView(ev: android.view.MotionEvent, v: View): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }
    }

    /** 嵌套在外层设置页 ScrollView 内的可滚动预览容器：滚动时禁止外层拦截，保证预览内上下滑动滚动课表。 */
    private inner class PreviewScrollView(ctx: android.content.Context) : ScrollView(ctx) {
        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun todayDayOfWeek(): Int {
        val cal = java.util.Calendar.getInstance()
        val calDay = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        return ((calDay + 5) % 7) + 1
    }

    /**
     * 动画速度预览：在预览区左右滑动切换周（跟手），松手按位移/速度切周或弹回，动画时长取自对应滑块。
     * play(mode, ms)：
     *   switch = 左滑切下一周：当前周卡左滑出、下一周卡从右侧滑入到位
     *   switchBack = 右滑切回上一周
     *   back   = 弹回原位
     *   cancel = 取消回弹（拖出较多回弹）
     */
    private inner class AnimPreviewView(ctx: android.content.Context) : android.view.View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var offsetX = 0f
        private var animator: android.animation.ValueAnimator? = null
        private var downX = 0f
        private var isDragging = false
        private val labels = listOf("第 5 周", "第 6 周", "第 7 周", "第 8 周",
            "第 9 周", "第 10 周", "第 11 周", "第 12 周")
        private var currentIndex = 0   // 当前主卡下标
        private var velocityTracker: android.view.VelocityTracker? = null
        private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        init { setBackgroundColor(android.graphics.Color.parseColor("#EEF0F2")) }

        /** 与主课表一致的滑动手感：快速甩动（速度判定）或拖过半（位移判定）即切周。 */
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x; isDragging = true; animator?.cancel()
                    velocityTracker = android.view.VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> if (isDragging) {
                    offsetX = (event.x - downX)
                    velocityTracker?.addMovement(event)
                    invalidate()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    isDragging = false
                    velocityTracker?.apply {
                        computeCurrentVelocity(1000)
                    }
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val dx = event.x - downX
                    val fling = kotlin.math.abs(vx) > 1200f && kotlin.math.abs(dx) > touchSlop * 2f
                    val dirIsNext = if (fling) vx < 0f else dx < 0f
                    if (fling || kotlin.math.abs(dx) > width * 0.5f) {
                        play(if (dirIsNext) "switch" else "switchBack",
                            prefs.getInt("anim_switch", 300).toLong())
                    } else {
                        play("back", prefs.getInt("anim_back", 300).toLong())
                    }
                    velocityTracker?.recycle(); velocityTracker = null
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    velocityTracker?.recycle(); velocityTracker = null
                    play("back", prefs.getInt("anim_back", 300).toLong())
                }
            }
            return true
        }

        fun play(mode: String, ms: Long) {
            animator?.cancel()
            val w = width.toFloat()
            val target = when (mode) {
                "switch"     -> -w * 1.05f   // 左滑：下一周进入
                "switchBack" -> w * 1.05f    // 右滑：切回上一周
                "cancel"     -> -w * 0.8f    // 取消：拖出较多回弹
                else         -> 0f           // back：弹回原位
            }
            animator = android.animation.ValueAnimator.ofFloat(offsetX, target).apply {
                duration = ms.coerceIn(50, 1000)
                addUpdateListener { offsetX = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        // 切周成功才改主卡；弹回不改
                        if (mode == "switch" && currentIndex < labels.size - 1) currentIndex++
                        else if (mode == "switchBack" && currentIndex > 0) currentIndex--
                        offsetX = 0f
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val cardW = w * 0.78f; val cardH = h * 0.56f
            val top = h * 0.2f
            val left = w * 0.11f
            // 主卡（当前周）——随 offsetX 平移
            drawCard(canvas, left + offsetX, top, cardW, cardH, labels[currentIndex])
            // 右邻周卡（下一周）——从右侧跟随进入
            labels.getOrNull(currentIndex + 1)?.let { next ->
                drawCard(canvas, left + cardW + w * 0.03f + offsetX, top, cardW, cardH, next)
            }
        }

        private fun drawCard(canvas: android.graphics.Canvas, x: Float, y: Float,
                             cw: Float, ch: Float, label: String) {
            val r = dp(6).toFloat()
            paint.color = android.graphics.Color.WHITE
            canvas.drawRoundRect(x, y, x + cw, y + ch, r, r, paint)
            // 顶部色条（模拟表头）
            paint.color = android.graphics.Color.parseColor("#E84E0F")
            canvas.drawRoundRect(x, y, x + cw, y + dp(4).toFloat(), r, r, paint)
            // 周次文字
            paint.color = android.graphics.Color.parseColor("#333333")
            paint.textSize = dp(14).toFloat()
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val baseline = y + ch * 0.58f
            canvas.drawText(label, x + cw / 2f, baseline, paint)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
