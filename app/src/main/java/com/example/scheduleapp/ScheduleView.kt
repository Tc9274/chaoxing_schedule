package com.example.scheduleapp

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * 自定义课表视图：左侧时间轴 + 顶部星期表头 + 课程块网格。
 * 纯 Canvas 绘制，无第三方依赖。
 */
class ScheduleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: ScheduleData? = null
    private var todayDay = 0   // 1=周一 … 7=周日；0 表示未知

    // 变更强调 + 显示名替换配置
    private var changeKeys: Set<String> = emptySet()
    private var nameMap: Map<String, String> = emptyMap()    // 全局：课名 -> 显示名
    private var locMap: Map<String, String> = emptyMap()     // 全局：位置 -> 显示位置
    private var oneName: Map<String, String> = emptyMap()    // 单节：key -> 显示名
    private var oneLoc: Map<String, String> = emptyMap()     // 单节：key -> 显示位置
    private var lessonRects: MutableList<Pair<RectF, Lesson>> = mutableListOf()
    var onLessonClick: ((Lesson) -> Unit)? = null

    private var headerH = dp(48f)
    private val timeW = dp(58f)
    private val labelPad = dp(8f)

    // 显示设置（用户可调，默认自适应）
    private var fontCourse = dp(12f)
    private var fontLoc = dp(10f)
    private var fontHeader = dp(15f)
    private var cellHOverride = 0f      // 0=自适应填满视口；>0 固定格子高（可滚动）
    private var nameY = 0.33f           // 课程名在块内垂直位置比例
    private var locY = 0.78f            // 位置在块内垂直位置比例
    private var maxSectionCount = 0

    // 按周切换：当前查看周
    private var currentWeek = 0
    private var maxWeek = 0
    var onWeekChanged: ((Int) -> Unit)? = null

    // 手势检测 + 平移动画
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var offsetX = 0f                       // 当前绘制水平偏移（跟手/动画）
    private var animator: ValueAnimator? = null
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val bgPaint = Paint().apply { color = Color.WHITE }
    private val gridPaint = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val headerPaint = Paint().apply {
        color = Color.WHITE; textSize = dp(15f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val timeTextPaint = Paint().apply {
        color = 0xFF666666.toInt(); textSize = dp(10f); textAlign = Paint.Align.CENTER
    }
    private val coursePaint = Paint().apply {
        color = Color.WHITE; textSize = dp(12f); textAlign = Paint.Align.CENTER
    }
    private val locPaint = Paint().apply {
        color = 0xFFE0E0E0.toInt(); textSize = dp(10f); textAlign = Paint.Align.CENTER
    }

    private val todayFill = Paint().apply { color = 0xFFFDEBD0.toInt() }
    private val headerTodayFill = Paint().apply { color = 0xFFF0B429.toInt() }
    private val headerNormalFill = Paint().apply { color = 0xFF2C7BB6.toInt() }
    private val courseFill = Paint().apply {
        color = 0xFF3F9BFC.toInt(); style = Paint.Style.FILL
    }
    private val changePaint = Paint().apply {
        color = 0xFFFF9800.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val corner = dp(6f)

    init { isClickable = true }

    /** week 不为空时，强制以该周作为当前查看周（用于滑动切周拉取的数据）。 */
    fun setData(schedule: ScheduleData, today: Int, week: Int? = null) {
        data = schedule
        todayDay = today
        maxSectionCount = schedule.sectionTimes.size
        maxWeek = schedule.maxWeek.coerceAtLeast(1)
        currentWeek = (week ?: schedule.currentWeek).coerceIn(1, maxWeek)
        requestLayout()
        invalidate()
    }

    /** 应用显示设置（字号/表头高/格子高/内容位置），主界面调用。 */
    fun setDisplayStyle(courseSize: Float, locSize: Float, headerSize: Float,
                        headerHpx: Float, cellHpx: Float, nameYRatio: Float, locYRatio: Float) {
        fontCourse = courseSize; fontLoc = locSize; fontHeader = headerSize
        headerH = headerHpx; cellHOverride = cellHpx
        nameY = nameYRatio; locY = locYRatio
        coursePaint.textSize = fontCourse
        locPaint.textSize = fontLoc
        headerPaint.textSize = fontHeader
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val h = if (cellHOverride > 0) {
            // 固定格子高：内容高度可能超出视口，交给外层 ScrollView 滚动
            (headerH + maxSectionCount * cellHOverride).toInt()
        } else {
            MeasureSpec.getSize(heightMeasureSpec)   // 自适应：填满视口
        }
        setMeasuredDimension(measuredWidth, h)
    }

    fun setChangeKeys(keys: Set<String>) {
        changeKeys = keys
        invalidate()
    }

    fun setDisplayMaps(nm: Map<String, String>, lm: Map<String, String>,
                       on: Map<String, String>, ol: Map<String, String>) {
        nameMap = nm; locMap = lm; oneName = on; oneLoc = ol
        invalidate()
    }

    /** 课程标识：星期|开始节次|课名。 */
    fun keyOf(lesson: Lesson): String = "${lesson.dayOfWeek}|${lesson.beginSection}|${lesson.course}"

    /** 跟手拖动切换周：手指移动时课表整体平移，松手后按位移决定切周或弹回。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                stopAnim()
                downX = event.x; downY = event.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!isDragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    isDragging = true
                }
                if (isDragging) {
                    offsetX = event.x - downX   // 跟手
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val dx = event.x - downX
                    isDragging = false
                    // 拖过约 40% 屏宽则切周，否则弹回（可随时中途反向回拉）
                    if (abs(dx) > width * 0.4f) switchWeek(dx < 0)
                    else animateOffset(offsetX, 0f, 220)
                    return true
                }
                for ((rect, lesson) in lessonRects) {
                    if (rect.contains(event.x, event.y)) {
                        onLessonClick?.invoke(lesson)
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                animateOffset(offsetX, 0f, 180)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 左滑(isNext=true)切到下一周，右滑切上一周；带滑出/滑入动画。 */
    private fun switchWeek(isNext: Boolean) {
        val target = currentWeek + (if (isNext) 1 else -1)
        if (target < 1 || target > maxWeek) {
            animateOffset(offsetX, 0f, 220)   // 到边界弹回
            return
        }
        val sign = if (isNext) -1f else 1f     // 当前屏滑向 -width/+width，新屏从对面进入
        animateOffset(offsetX, sign * width, 200) {
            onWeekChanged?.invoke(target)
            offsetX = -sign * width
            animateOffset(offsetX, 0f, 200)
        }
    }

    /** 平移动画（弹性减速），可选结束回调。 */
    private fun animateOffset(from: Float, to: Float, duration: Long, end: (() -> Unit)? = null) {
        stopAnim()
        val anim = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { offsetX = it.animatedValue as Float; invalidate() }
        }
        animator = anim
        anim.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
            override fun onAnimationCancel(a: Animator) { animator = null; end?.invoke() }
            override fun onAnimationEnd(a: Animator) { animator = null; end?.invoke() }
        })
        anim.start()
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    /** 解析 weeks 字符串（如 "1-16周"、"1-8,10-20周"、"5,7周"）为周次集合。 */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgPaint.color)
        val d = data ?: return
        if (d.lessons.isEmpty() && d.sectionTimes.isEmpty()) {
            drawEmpty(canvas)
            return
        }

        // 整体随 offsetX 平移（跟手拖动 / 切周动画）
        canvas.save()
        canvas.translate(offsetX, 0f)

        val sectionCount = d.sectionTimes.size.coerceAtLeast(1)
        val cellH = if (cellHOverride > 0) cellHOverride else (height - headerH) / sectionCount.toFloat()
        val bodyW = width - timeW
        val dayW = bodyW / 7f

        // 表头
        drawHeader(canvas, dayW)

        // 时间轴 + 网格
        for (s in 0 until sectionCount) {
            val top = headerH + s * cellH
            // 横向网格线
            canvas.drawLine(timeW, top, width.toFloat(), top, gridPaint)
            // 时间文字（节次 + 起时）
            val time = d.sectionTimes.getOrNull(s)?.second ?: ""
            val start = time.substringBefore("-")
            val label = if (start.isNotEmpty()) "${s + 1}节\n$start" else "${s + 1}节"
            timeTextPaint.textAlign = Paint.Align.CENTER
            drawMultiline(canvas, label, timeW / 2f, top + cellH / 2f, timeTextPaint, dp(10f))
        }
        // 底边 + 左边线
        canvas.drawLine(timeW, height.toFloat(), width.toFloat(), height.toFloat(), gridPaint)
        canvas.drawLine(timeW, headerH, timeW, height.toFloat(), gridPaint)

        // 课程块（接口已按周返回，显示该周全部课程）
        lessonRects.clear()
        for (lesson in d.lessons) {
            if (lesson.dayOfWeek < 1 || lesson.dayOfWeek > 7) continue
            val dayCol = lesson.dayOfWeek - 1
            val top = headerH + (lesson.beginSection - 1) * cellH
            val bottom = top + lesson.length * cellH - dp(3f)
            val left = timeW + dayCol * dayW + dp(2f)
            val right = left + dayW - dp(4f)
            if (bottom <= top || right <= left) continue
            val rect = RectF(left, top, right, bottom)
            lessonRects.add(rect to lesson)
            canvas.drawRoundRect(rect, corner, corner, courseFill)
            // 变更强调：边框变橙
            if (keyOf(lesson) in changeKeys) {
                canvas.drawRoundRect(rect, corner, corner, changePaint)
            }
            // 显示名替换（单节优先，其次全局）
            val key = keyOf(lesson)
            val displayName = oneName[key] ?: nameMap[lesson.course] ?: lesson.course
            val displayLoc = oneLoc[key] ?: locMap[lesson.location] ?: lesson.location
            // 课程名 + 教室：按整块高度布局，自适应字号完整显示
            val cx = (left + right) / 2f
            val blockH = lesson.length * cellH   // 跨节块用整块高度，空间充足
            coursePaint.textSize = fontCourse
            drawFixed(canvas, displayName, cx, top + blockH * nameY, coursePaint,
                right - left - labelPad, blockH * 0.5f)
            // 教室
            if (displayLoc.isNotEmpty()) {
                locPaint.textSize = fontLoc
                drawFixed(canvas, displayLoc, cx, top + blockH * locY, locPaint,
                    right - left - labelPad, blockH * 0.4f)
            }
        }

        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, dayW: Float) {
        val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        for (i in weekdays.indices) {
            val left = timeW + i * dayW
            val right = left + dayW
            val isToday = (i + 1) == todayDay
            canvas.drawRect(left, 0f, right, headerH, if (isToday) headerTodayFill else headerNormalFill)
            // 今日标记
            if (isToday) headerPaint.color = Color.WHITE
            drawTextCenter(canvas, weekdays[i], (left + right) / 2f, headerH / 2f, headerPaint)
        }
        canvas.drawRect(0f, 0f, timeW, headerH, headerNormalFill)
        drawTextCenter(canvas, "节次", timeW / 2f, headerH / 2f, headerPaint)
        headerPaint.color = Color.WHITE
        canvas.drawLine(timeW, headerH, width.toFloat(), headerH, gridPaint)
    }

    private fun drawEmpty(canvas: Canvas) {
        val p = Paint().apply { color = 0xFF888888.toInt(); textSize = dp(15f); textAlign = Paint.Align.CENTER }
        canvas.drawText("暂无课表数据", width / 2f, height / 2f, p)
    }

    private fun drawTextCenter(canvas: Canvas, text: String, x: Float, cy: Float, paint: Paint) {
        val fm = paint.fontMetrics
        canvas.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun drawMultiline(canvas: Canvas, text: String, x: Float, cy: Float, paint: Paint, lineH: Float) {
        val lines = text.split("\n")
        val total = lines.size * lineH
        val top = cy - total / 2f
        lines.forEachIndexed { i, ln ->
            val fm = paint.fontMetrics
            canvas.drawText(ln, x, top + i * lineH - (fm.ascent + fm.descent) / 2f + lineH / 2f, paint)
        }
    }

    /**
     * 固定字号绘制（居中、可换行）。所有课程用同一字号，
     * 换行后仍超出块高则在末行末尾加省略号。
     */
    private fun drawFixed(canvas: Canvas, text: String, cx: Float, cy: Float,
                          paint: Paint, maxW: Float, maxH: Float) {
        if (text.isEmpty()) return
        val lineH = paint.textSize * 1.15f
        val maxLines = (maxH / lineH).toInt().coerceAtLeast(1)
        val lines = wrapLines(text, maxW, paint)
        val truncated = lines.size > maxLines
        val shown = if (truncated) lines.take(maxLines) else lines
        val fm = paint.fontMetrics
        val top = cy - shown.size * lineH / 2f
        shown.forEachIndexed { i, ln ->
            var out = ln
            if (truncated && i == shown.size - 1) {
                var s = ln
                while (s.isNotEmpty() && paint.measureText(s + "…") > maxW) s = s.dropLast(1)
                out = s + "…"
            }
            canvas.drawText(out, cx, top + (i + 1) * lineH - (fm.ascent + fm.descent) / 2f, paint)
        }
    }

    private fun wrapLines(text: String, maxW: Float, paint: Paint): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (sb.isNotEmpty() && paint.measureText(sb.toString() + ch) > maxW) {
                lines.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        return lines
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
