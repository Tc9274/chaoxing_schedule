package com.example.scheduleapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 密码框右侧的「眼睛」图标：睁眼=明文，带斜线=密文；点击切换。 */
class EyeView(context: Context) : View(context) {

    /** 当前是否为明文。 */
    var opened = false
        set(value) { field = value; invalidate() }

    /** 切换回调，参数为切换后的状态。 */
    var onToggle: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B6EB5")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B6EB5")
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        setOnClickListener {
            opened = !opened
            onToggle?.invoke(opened)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (44 * density).toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rx = 10f * density
        val ry = 6f * density
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, stroke)
        canvas.drawCircle(cx, cy, 2.8f * density, solid)
        if (!opened) {
            canvas.drawLine(cx - rx, cy + ry * 1.6f, cx + rx, cy - ry * 1.6f, stroke)
        }
    }
}
