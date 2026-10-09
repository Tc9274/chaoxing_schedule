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
 * 设置界面：① 填写自己的超星课表链接；② 显示设置（字号/排版）。
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }

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
            minLines = 3
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        input.setText(prefs.getString("curriculum_uuid", ScheduleApi.DEFAULT_UUID) ?: ScheduleApi.DEFAULT_UUID)
        root.addView(input, lpWithMargin(0, 0, 0, dp(10)))
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
            text = "调节课表的字体大小和排版，拖动滑块即实时生效。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(sliderRow("font_course", "课程名字号", 10..22,
            init = prefs.getFloat("font_course", dp(12).toFloat()),
            text = { n -> "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_course", dp(n).toFloat()).apply() }))

        root.addView(sliderRow("font_loc", "位置字号", 8..18,
            init = prefs.getFloat("font_loc", dp(10).toFloat()),
            text = { n -> "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_loc", dp(n).toFloat()).apply() }))

        root.addView(sliderRow("font_header", "表头字号", 12..22,
            init = prefs.getFloat("font_header", dp(15).toFloat()),
            text = { n -> "${n} dp" },
            save = { n -> prefs.edit().putFloat("font_header", dp(n).toFloat()).apply() }))

        root.addView(sliderRow("header_h", "表头高度", 40..80,
            init = prefs.getFloat("header_h", dp(48).toFloat()),
            text = { n -> "${n} dp" },
            save = { n -> prefs.edit().putFloat("header_h", dp(n).toFloat()).apply() }))

        root.addView(sliderRow("cell_h", "格子高度", 0..180,
            init = prefs.getFloat("cell_h", 0f),
            text = { n -> if (n == 0) "自动适配" else "${n} dp（超高需上下滚动）" },
            save = { n -> prefs.edit().putFloat("cell_h",
                if (n == 0) 0f else dp(n).toFloat()).apply() }))

        root.addView(sliderRow("name_y", "课程名上下位置", 0..1000,
            init = prefs.getFloat("name_y", 0.33f),
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("name_y", n / 1000f).apply() }))

        root.addView(sliderRow("loc_y", "位置上下位置", 0..1000,
            init = prefs.getFloat("loc_y", 0.78f),
            text = { n -> "${(n / 10)}%" },
            save = { n -> prefs.edit().putFloat("loc_y", n / 1000f).apply() }))

        root.addView(spacer())
        root.addView(Button(this).apply {
            text = "恢复默认显示"
            setOnClickListener {
                prefs.edit()
                    .remove("font_course").remove("font_loc").remove("font_header")
                    .remove("header_h").remove("cell_h").remove("name_y").remove("loc_y")
                    .apply()
                recreate()
                Toast.makeText(this@SettingsActivity, "已恢复默认显示设置", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(scroll)
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
        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(16), 0, dp(16)) } }

    /** 一个滑块行：标签 + SeekBar，拖动即保存。 */
    private fun sliderRow(
        prefKey: String, title: String, range: IntRange,
        init: Float, text: (Int) -> String, save: (Int) -> Unit
    ): LinearLayout {
        val pxToProgress: (Float) -> Int = { px -> if (px <= 0f) range.first else (px / density).roundToInt().coerceIn(range) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, dp(8), 0, 0)
        }
        fun refresh(progress: Int) { label.text = "$title：${text(progress)}" }
        val bar = SeekBar(this).apply {
            max = range.last - range.first
            progress = pxToProgress(init) - range.first
            setPadding(0, 0, 0, 0)
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
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
