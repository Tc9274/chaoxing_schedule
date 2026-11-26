package com.example.scheduleapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 周次选择网格（5 列 × 若干行），点击切换选中，按住滑动可连续多选 / 连续取消。
 * 纯 Canvas 绘制，不依赖布局资源。
 */
@SuppressLint("ViewConstructor")
class WeekPickerView(context: Context) : View(context) {

    private val cols = 5
    var maxWeek: Int = 20
        set(v) {
            field = v.coerceAtLeast(1)
            requestLayout()
            invalidate()
        }

    private val selected = sortedSetOf<Int>()

    /** 已有课冲突的周次：格子标红提示，但仍可正常点选。 */
    var conflictWeeks: Set<Int> = emptySet()
        set(v) {
            field = v
            invalidate()
        }

    /** 用户手动改动格子时回调，用于清空下方“单周/双周/全选/本周”的单选状态。 */
    var onChanged: (() -> Unit)? = null

    private val gap = dp(9f)
    private val radius = dp(10f)

    /** 四周留白：描边是以矩形边居中绘制，贴边会被 View 边界裁掉一半，这里留出半个描边宽度。 */
    private val edge = dp(1f)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFFD0D7DE.toInt()
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val rows: Int get() = (maxWeek + cols - 1) / cols

    private var cell = 0f

    private var dragState = false   // 本次手势把格子设成“选中”还是“取消”
    private var dragCell = -1

    fun setWeeks(list: List<Int>) {
        selected.clear()
        for (w in list) if (w in 1..maxWeek) selected.add(w)
        invalidate()
    }

    fun weeks(): List<Int> = selected.toList()

    private val conflictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0xFFFF7B72.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cell = (w - edge * 2 - gap * (cols - 1)) / cols
        val h = (edge * 2 + rows * cell + (rows - 1) * gap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF()
        numPaint.textSize = cell * 0.34f
        val inset = dp(0.6f)   // 描边宽度的一半，保证描边完整落在 View 内
        for (i in 1..maxWeek) {
            val idx = i - 1
            val row = idx / cols
            val col = idx % cols
            val left = edge + col * (cell + gap)
            val top = edge + row * (cell + gap)
            r.set(left + inset, top + inset, left + cell - inset, top + cell - inset)
            val on = selected.contains(i)
            val conflict = i in conflictWeeks
            when {
                on && conflict -> {
                    bgPaint.color = 0xFFFF7B72.toInt()   // 选中且该周已有课：珊瑚红（与蓝色同色系对比，不刺眼）
                    canvas.drawRoundRect(r, radius, radius, bgPaint)
                }
                on -> {
                    bgPaint.color = 0xFF3C9BFA.toInt()
                    canvas.drawRoundRect(r, radius, radius, bgPaint)
                }
                conflict -> {
                    bgPaint.color = 0xFFFFF3F2.toInt()   // 未选中的冲突周：浅红底 + 红框红字
                    canvas.drawRoundRect(r, radius, radius, bgPaint)
                    canvas.drawRoundRect(r, radius, radius, conflictPaint)
                }
                else -> {
                    bgPaint.color = Color.WHITE
                    canvas.drawRoundRect(r, radius, radius, bgPaint)
                    canvas.drawRoundRect(r, radius, radius, strokePaint)
                }
            }
            numPaint.color = when {
                on -> Color.WHITE
                conflict -> 0xFFD9363E.toInt()
                else -> 0xFF333333.toInt()
            }
            val baseline = r.centerY() - (numPaint.ascent() + numPaint.descent()) / 2f
            canvas.drawText("$i", r.centerX(), baseline, numPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = cellAt(event.x, event.y) ?: return true
                dragCell = idx
                dragState = !selected.contains(idx)
                apply(idx, dragState)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = cellAt(event.x, event.y) ?: return true
                if (dragCell < 0) {
                    // 极端情况：DOWN 没落到格子上，从当前位置起算
                    dragCell = idx
                    dragState = !selected.contains(idx)
                    apply(idx, dragState)
                    return true
                }
                if (idx != dragCell) {
                    // 快速滑动时事件会跳格，把中间跨过的格子一起补上
                    val from = minOf(dragCell, idx)
                    val to = maxOf(dragCell, idx)
                    for (w in from..to) apply(w, dragState)
                    dragCell = idx
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun apply(week: Int, on: Boolean) {
        val changed = if (on) selected.add(week) else selected.remove(week)
        if (changed) {
            invalidate()
            onChanged?.invoke()
        }
    }

    /** 坐标 → 周次；不在任何格子里返回 null。 */
    private fun cellAt(x: Float, y: Float): Int? {
        if (cell <= 0f || x < 0 || y < 0) return null
        val col = ((x - edge) / (cell + gap)).toInt()
        val row = ((y - edge) / (cell + gap)).toInt()
        if (col !in 0 until cols) return null
        // 落在格子之间的空隙里也算该格（更宽容的命中判定）
        val week = row * cols + col + 1
        return if (week in 1..maxWeek) week else null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
