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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 标题
        root.addView(TextView(this).apply {
            text = "课表设置"
            textSize = 20f
            setTextColor(Color.parseColor("#17365D"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
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
        input.setText(prefs.getString("curriculum_uuid", ScheduleApi.DEFAULT_UUID) ?: ScheduleApi.DEFAULT_UUID)
        root.addView(input, lpWithMargin(0, 0, 0, dp(6)))
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
        root.addView(Button(this).apply {
            text = "恢复默认课表"
            setOnClickListener {
                prefs.edit().putString("curriculum_uuid", ScheduleApi.DEFAULT_UUID).apply()
                input.setText(ScheduleApi.DEFAULT_UUID)
                Toast.makeText(this@SettingsActivity, "已恢复默认课表", Toast.LENGTH_SHORT).show()
            }
        })

        // ===== 显示设置 =====
        root.addView(spacer())
        root.addView(sectionTitle("显示设置"))
        root.addView(TextView(this).apply {
            text = "拖动滑块实时生效，上方预览可看到效果。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(8))
        })
        // 效果预览（内嵌课表，随滑块实时变化）
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { setMargins(0, 0, 0, dp(8)) })
        val raw = prefs.getString("json", null)
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
            initValue = prefs.getFloat("font_course", dp(12).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_course",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() }))

        root.addView(sliderRow("font_loc", "位置字号", 0..15,
            initValue = prefs.getFloat("font_loc", dp(10).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_loc",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() }))

        root.addView(sliderRow("font_header", "表头字号", 0..15,
            initValue = prefs.getFloat("font_header", dp(15).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_header",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() }))

        root.addView(sliderRow("header_h", "表头高度", 0..60,
            initValue = prefs.getFloat("header_h", dp(48).toFloat()) / density,
            text = { n -> if (n == 0) "自动" else "${n} dp" },
            save = { n -> prefs.edit().putFloat("header_h",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() }))

        root.addView(sliderRow("cell_h", "格子高度", 0..50,
            initValue = prefs.getFloat("cell_h", 0f) / density,
            text = { n -> if (n == 0) "自动适配" else "${n} dp（超高需上下滚动）" },
            save = { n -> prefs.edit().putFloat("cell_h",
                if (n == 0) 0f else dp(n).toFloat()).apply(); refreshPreview() }))

        root.addView(sliderRow("name_y", "课程名上下位置", 0..1000,
            initValue = prefs.getFloat("name_y", 0.33f) * 1000,
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("name_y", n / 1000f).apply(); refreshPreview() }))

        root.addView(sliderRow("loc_y", "位置上下位置", 0..1000,
            initValue = prefs.getFloat("loc_y", 0.78f) * 1000,
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("loc_y", n / 1000f).apply(); refreshPreview() }))

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
        root.addView(sliderRow("anim_switch", "切周动画", 300..1000,
            initValue = prefs.getInt("anim_switch", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_switch", n).apply() },
            onStop = { n -> animPreview.play("switch", n.toLong()) }))
        root.addView(sliderRow("anim_back", "弹回动画", 300..1000,
            initValue = prefs.getInt("anim_back", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_back", n).apply() },
            onStop = { n -> animPreview.play("back", n.toLong()) }))
        root.addView(sliderRow("anim_cancel", "取消回弹", 300..1000,
            initValue = prefs.getInt("anim_cancel", 300).toFloat(),
            text = { n -> "${n} ms" },
            save = { n -> prefs.edit().putInt("anim_cancel", n).apply() },
            onStop = { n -> animPreview.play("cancel", n.toLong()) }))
        // 进入设置时先按默认值播一次切周动画，让用户看到预览效果
        animPreview.post { animPreview.play("switch", prefs.getInt("anim_switch", 300).toLong()) }

        root.addView(spacer())
        root.addView(Button(this).apply {
            text = "恢复默认显示"
            setOnClickListener {
                prefs.edit()
                    .remove("font_course").remove("font_loc").remove("font_header")
                    .remove("header_h").remove("cell_h").remove("name_y").remove("loc_y")
                    .remove("anim_switch").remove("anim_back").remove("anim_cancel")
                    .apply()
                recreate()
                Toast.makeText(this@SettingsActivity, "已恢复默认显示设置", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(scroll)
    }

    /** 滑块变化后刷新预览显示。 */
    private fun refreshPreview() {
        preview.setDisplayStyle(
            prefs.getFloat("font_course", dp(12).toFloat()),
            prefs.getFloat("font_loc", dp(10).toFloat()),
            prefs.getFloat("font_header", dp(15).toFloat()),
            prefs.getFloat("header_h", dp(48).toFloat()),
            prefs.getFloat("cell_h", 0f),
            prefs.getFloat("name_y", 0.33f),
            prefs.getFloat("loc_y", 0.78f)
        )
        preview.invalidate()
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
        onStop: ((Int) -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, dp(8), 0, 0)
        }
        fun refresh(progress: Int) { label.text = "$title：${text(progress)}" }
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

    private fun lpWithMargin(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(l, t, r, b)
        }

    private fun todayDayOfWeek(): Int {
        val cal = java.util.Calendar.getInstance()
        val calDay = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        return ((calDay + 5) % 7) + 1
    }

    /**
     * 动画速度预览：用与真实课表一致的"相邻周卡片平移"动画来演示。
     * play(mode, ms)：
     *   switch = 切周动画：当前周卡左滑出、下一周卡从右侧滑入到位
     *   back   = 弹回动画：当前周卡拖出一点后弹回原位
     *   cancel = 取消回弹：当前周卡拖出一半后弹回原位
     */
    private inner class AnimPreviewView(ctx: android.content.Context) : android.view.View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var offsetX = 0f
        private var animator: android.animation.ValueAnimator? = null

        init { setBackgroundColor(android.graphics.Color.parseColor("#EEF0F2")) }

        fun play(mode: String, ms: Long) {
            animator?.cancel()
            val w = width.toFloat()
            val target = when (mode) {
                "switch" -> -w * 1.05f          // 当前周滑出，下一周进入
                "back"   -> -w * 0.5f           // 拖出一点弹回
                else     -> -w * 0.8f           // cancel：拖出较多回弹
            }
            animator = android.animation.ValueAnimator.ofFloat(offsetX, target).apply {
                duration = ms.coerceIn(50, 1000)
                addUpdateListener { offsetX = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        offsetX = 0f   // 演示结束归位（switch 时已"切到下一周"，直接复原再供下次演示）
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
            // 当前周卡（第 5 周）——随 offsetX 左移
            drawCard(canvas, left + offsetX, top, cardW, cardH, "第 5 周")
            // 下一周卡（第 6 周）——位于当前周右侧，同步被带入
            drawCard(canvas, left + cardW + w * 0.03f + offsetX, top, cardW, cardH, "第 6 周")
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
