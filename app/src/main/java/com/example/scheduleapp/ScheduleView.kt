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
import android.view.VelocityTracker
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
    private val headerGap = dp(3f)   // 表头行与第一节之间的缝
    private var timeW = dp(58f)   // 节次列宽度（可调）
    private val labelPad = dp(8f)

    // 显示设置（用户可调，默认自适应）
    private var fontCourse = dp(12f)
    private var fontLoc = dp(10f)
    private var fontHeader = dp(15f)
    private var cellHOverride = 0f      // 0=自适应填满视口；>0 固定格子高（可滚动）
    private var nameY = 0.33f           // 课程名在块内垂直位置比例
    private var locY = 0.78f            // 位置在块内垂直位置比例
    private var maxSectionCount = 0
    private var showSat = true      // 是否显示周六
    private var showSun = true      // 是否显示周日
    private var showHeaderDate = true   // 表头每天下方显示当天日期

    // 当前周每天的日期（index 1..7，格式 MM.dd），由该周课程日期推算
    private var dayDates = arrayOfNulls<String>(8)
    private var timeTwoLine = true  // 节次时间两行（起/止分行）或一行（起-止）

    // 按周切换：当前查看周
    private var currentWeek = 0
    private var maxWeek = 0
    var onWeekChanged: ((Int) -> Unit)? = null

    // 相邻周数据（滑动时与当前周同时平移，避免切换空白）
    private var prevData: ScheduleData? = null
    private var nextData: ScheduleData? = null

    // 动画时长（用户可调，毫秒）
    private var animSwitchMs = 300L   // 切周动画
    private var animBackMs = 300L     // 未切周时的弹回
    private var animCancelMs = 300L   // 触摸取消时的回弹

    // 手势检测 + 平移动画
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var offsetX = 0f                       // 当前绘制水平偏移（跟手/动画）
    private var animator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val bgPaint = Paint().apply { color = Color.WHITE }
    private val gridPaint = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val headerPaint = Paint().apply {
        color = Color.WHITE; textSize = dp(15f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    // 表头每天下方的日期小字
    private val dateHeaderPaint = Paint().apply {
        color = Color.WHITE; textSize = dp(10f); textAlign = Paint.Align.CENTER
    }
    private val timeTextPaint = Paint().apply {
        color = 0xFF666666.toInt(); textSize = dp(10f); textAlign = Paint.Align.CENTER
    }
    private val coursePaint = Paint().apply {
        color = Color.WHITE; textSize = dp(12f); textAlign = Paint.Align.CENTER
    }
    private val locPaint = Paint().apply {
        color = Color.WHITE; textSize = dp(10f); textAlign = Paint.Align.CENTER
    }
    private var textColor = Color.WHITE   // 课程/位置文字颜色（黑/白/灰切换）
    /** 设置文字颜色：黑/白/灰。 */
    fun setTextColor(color: Int) {
        textColor = color
        coursePaint.color = color
        locPaint.color = color
        invalidate()
    }

    // 不同课程的柔和淡色池（同课程恒同色、跨周稳定）
    private val courseColors = intArrayOf(
        0xFFA8D8EA.toInt(), // 淡蓝
        0xFFB9E4C9.toInt(), // 淡绿
        0xFFF6D6AD.toInt(), // 淡橙
        0xFFD5CAEF.toInt(), // 淡紫
        0xFFF4C7C3.toInt(), // 淡粉
        0xFFC3E8E3.toInt(), // 淡青
        0xFFFFE3B0.toInt(), // 淡黄
        0xFFBDE0FE.toInt(), // 浅蓝
        0xFFDFD3C3.toInt(), // 淡褐
        0xFFCCEBF1.toInt(), // 浅青蓝
    )
    /** 用户自定义课程颜色回调：输入课程名，返回自定义颜色或 null（null=用默认哈希色）。 */
    var customColor: ((String) -> Int?)? = null

    /** 默认课程配色池（浅色系、色相差明显）：按课程出现顺序分配，每课不同；池用完改用随机浅色不重复。 */
    private val defaultPalette = intArrayOf(
        0xFFEF9A9A.toInt(),  // 浅红
        0xFFFFCC80.toInt(),  // 浅橙
        0xFFD7CCC8.toInt(),  // 浅棕
        0xFFA5D6A7.toInt(),  // 浅绿
        0xFF80CBC4.toInt(),  // 浅青
        0xFF90CAF9.toInt(),  // 浅蓝
        0xFF9FA8DA.toInt(),  // 浅靛蓝
        0xFFCE93D8.toInt(),  // 浅紫
        0xFFF48FB1.toInt(),  // 浅粉
        0xFFBCAAA4.toInt()   // 浅褐
    )
    private val assignedColors = HashMap<String, Int>()   // 课程名 -> 已分配默认色
    private val usedColors = HashSet<Int>()               // 已占用色（含随机），用于不重复
    private var poolCursor = 0

    private fun randomDistinctColor(): Int {
        val rnd = java.util.Random()
        var c: Int
        do {
            val r = 160 + rnd.nextInt(96)
            val g = 160 + rnd.nextInt(96)
            val b = 160 + rnd.nextInt(96)
            c = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        } while (c in usedColors)
        return c
    }

    /** "仅这一节"的颜色覆盖回调：输入 星期|节次|课程，返回该节颜色或 0（无覆盖）。 */
    var oneColor: ((String) -> Int)? = null

    private fun courseColor(lesson: Lesson): Int {
        val key = "${lesson.dayOfWeek}|${lesson.beginSection}|${lesson.course}"
        oneColor?.invoke(key)?.let { if (it != 0) return it }
        customColor?.invoke(lesson.course)?.let { if (it != 0) return it }
        assignedColors[lesson.course]?.let { return it }
        val color = if (poolCursor < defaultPalette.size) defaultPalette[poolCursor++]
                    else randomDistinctColor()
        assignedColors[lesson.course] = color
        usedColors.add(color)
        return color
    }

    /** 返回当前所有已占用颜色（供随机选色时避开）。 */
    fun colorsInUse(): Set<Int> = usedColors.toSet()

    /** 为课程分配一个不与现有色重复的随机浅色（弹窗"随机"按钮）。 */
    fun assignRandomColor(course: String): Int {
        val color = randomDistinctColor()
        assignedColors[course] = color
        usedColors.add(color)
        return color
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
    private val weekendLinePaint = Paint().apply {
        color = 0xFF4CAF50.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val weekColPaint = Paint().apply { color = 0xFFDFF5E1.toInt() }   // 本周节次列浅绿
    private val headerWeekendFill = Paint().apply { color = 0xFFC6EAC9.toInt() } // 本周表头节次格浅绿

    init { isClickable = true }

    /** week 不为空时，强制以该周作为当前查看周（用于滑动切周拉取的数据）。 */
    fun setData(schedule: ScheduleData, today: Int, week: Int? = null) {
        data = schedule
        todayDay = today
        maxSectionCount = schedule.sectionTimes.size
        maxWeek = schedule.maxWeek.coerceAtLeast(1)
        currentWeek = (week ?: schedule.currentWeek).coerceIn(1, maxWeek)
        buildDayDates(schedule)
        requestLayout()
        invalidate()
    }

    /** 从该周课程的日期推算每天日期（MM.dd）。优先用带年的日期反推整周；否则直接映射有课的天的日期。 */
    private fun buildDayDates(s: ScheduleData) {
        dayDates = arrayOfNulls(8)
        val anchor = s.lessons.firstOrNull { parseDateWithYear(it.date) != null }
        if (anchor != null) {
            val (y, mo, d) = parseDateWithYear(anchor.date)!!
            val cal = java.util.Calendar.getInstance()
            cal.clear()
            cal.set(y, mo - 1, d)
            // 该课所在星期 anchor.dayOfWeek（1=周一），反推该周周一
            cal.add(java.util.Calendar.DAY_OF_YEAR, -(anchor.dayOfWeek.coerceIn(1, 7) - 1))
            for (i in 1..7) {
                dayDates[i] = String.format("%02d.%02d",
                    cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return
        }
        // 无年份信息：直接映射有课天的日期
        for (l in s.lessons) {
            if (l.date.isBlank() || dayDates[l.dayOfWeek] != null) continue
            val m = Regex("(\\d{1,2})-(\\d{1,2})").find(l.date) ?: continue
            dayDates[l.dayOfWeek] = String.format("%02d.%02d", m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    }

    private fun parseDateWithYear(date: String): Triple<Int, Int, Int>? {
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(date) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
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

    /** 设置相邻周数据（上下周的缓存），供滑动时同步平移。 */
    fun setAdjacentData(prev: ScheduleData?, next: ScheduleData?) {
        prevData = prev
        nextData = next
        invalidate()
    }

    /** 设置节次列（时间轴）宽度（像素）。 */
    fun setTimeWidth(px: Float) {
        timeW = px
        requestLayout()
        invalidate()
    }

    /** 设置动画时长（毫秒）：切周 / 弹回 / 取消回弹。 */
    fun setAnimDurations(switchMs: Int, backMs: Int, cancelMs: Int) {
        animSwitchMs = switchMs.toLong().coerceIn(50, 1000)
        animBackMs = backMs.toLong().coerceIn(50, 1000)
        animCancelMs = cancelMs.toLong().coerceIn(50, 1000)
    }

    /** 设置节次时间显示方式：true=起/止分两行（8:40 / 10:05）；false=一行（8:40-10:05）。 */
    fun setTimeTwoLine(twoLine: Boolean) {
        timeTwoLine = twoLine
        invalidate()
    }

    /** 设置是否显示周六、周日两列。被关闭的列不画课程，若该列当周有课则在列两侧画绿线提示。 */
    fun setShowDays(sat: Boolean, sun: Boolean) {
        showSat = sat
        showSun = sun
        invalidate()
    }

    /** 设置表头每天下方是否显示当天日期。 */
    fun setShowHeaderDate(show: Boolean) {
        showHeaderDate = show
        invalidate()
    }

    /** 课程标识：星期|开始节次|课名。 */
    fun keyOf(lesson: Lesson): String = "${lesson.dayOfWeek}|${lesson.beginSection}|${lesson.course}"

    /** 跟手拖动切换周：手指移动时课表整体平移，松手后按位移或甩动速度决定切周或弹回。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                stopAnim()
                downX = event.x; downY = event.y
                isDragging = false
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
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
                    velocityTracker?.apply {
                        addMovement(event)
                        computeCurrentVelocity(1000)
                    }
                    val vx = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle(); velocityTracker = null
                    isDragging = false
                    // 甩动：快速横向挥动（速度够大且有一定位移）也切周
                    val fling = abs(vx) > 1200f && abs(dx) > touchSlop * 2f
                    if (fling || abs(dx) > width * 0.4f) switchWeek(dx < 0)
                    else animateOffset(offsetX, 0f, animBackMs)
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
                velocityTracker?.recycle(); velocityTracker = null
                animateOffset(offsetX, 0f, animCancelMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 左滑(isNext=true)切到下一周，右滑切上一周；一段动画：当前屏滑出、相邻屏同步滑入，到位即切，无空白。 */
    private fun switchWeek(isNext: Boolean, dur: Long = animSwitchMs) {
        val target = currentWeek + (if (isNext) 1 else -1)
        if (target < 1 || target > maxWeek) {
            animateOffset(offsetX, 0f, animBackMs)   // 到边界弹回
            return
        }
        val sign = if (isNext) -1f else 1f     // 当前屏滑向 -width/+width，相邻屏从对面进入
        animateOffset(offsetX, sign * width, dur) {
            // 先切数据（此时新周仍停在新周相邻屏的位置，平滑无闪），再归位
            onWeekChanged?.invoke(target)
            offsetX = 0f
            invalidate()
        }
    }

    /** 外部触发一步切周动画（如点击周数回本周）。 */
    fun slideStep(isNext: Boolean) = switchWeek(isNext)

    /** 朝目标周滑一步（方向按当前 currentWeek 计算，避免与外部 viewWeek 不同步）。回本周/定位用较快的固定时长。 */
    fun slideTo(target: Int) {
        if (target == currentWeek) return
        switchWeek(target > currentWeek, 120L)
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
        // 主屏：当前周
        drawSchedule(canvas, d, offsetX, currentWeek, true)
        // 相邻屏：滑动时从对面同步进入，避免切换空白
        if (offsetX < 0) {
            nextData?.let { drawSchedule(canvas, it, offsetX + width, currentWeek + 1, false) }
        } else if (offsetX > 0) {
            prevData?.let { drawSchedule(canvas, it, offsetX - width, currentWeek - 1, false) }
        }
    }

    /** 绘制一个周的完整课表（表头、网格、课程），整体按 xOff 平移。week 为该屏显示的周号。 */
    private fun drawSchedule(canvas: Canvas, d: ScheduleData, xOff: Float, week: Int, collectRects: Boolean) {
        canvas.save()
        canvas.translate(xOff, 0f)

        val sectionCount = d.sectionTimes.size.coerceAtLeast(1)
        val cellH = if (cellHOverride > 0) cellHOverride else (height - headerH - headerGap) / sectionCount.toFloat()
        val bodyW = width - timeW

        // 显示的天列：周一~周五恒显；周六、周日按开关决定（关闭则连表头一起消失，布局压缩）
        val days = mutableListOf("周一", "周二", "周三", "周四", "周五")
        if (showSat) days.add("周六")
        if (showSun) days.add("周日")
        val dayIndex = HashMap<Int, Int>()
        var di = 0
        for (day in 1..5) dayIndex[day] = di++
        if (showSat) dayIndex[6] = di++
        if (showSun) dayIndex[7] = di++
        val dayW = bodyW / days.size

        // 表头（该屏是本周时整行浅绿；当天保持黄色）
        drawHeader(canvas, dayW, days, week == d.currentWeek)

        // 时间轴 + 网格
        for (s in 0 until sectionCount) {
            val top = headerH + headerGap + s * cellH
            // 横向网格线
            canvas.drawLine(timeW, top, width.toFloat(), top, gridPaint)
            // 时间文字（节次 + 起止时间；两行=左"节次"+右上下时间隔开，一行=起-止）
            val time = d.sectionTimes.getOrNull(s)?.second ?: ""
            val start = time.substringBefore("-")
            val end = time.substringAfter("-")
            timeTextPaint.textAlign = Paint.Align.CENTER
            if (timeTwoLine && start.isNotEmpty() && end.isNotEmpty()) {
                // 按文本实际宽度整体居中："节"(数字上/节下) + 间隔 + 起止时间 组成一块，块中心对准节次列正中
                val gap = dp(1f)
                val cw = maxOf(timeTextPaint.measureText("${s + 1}"), timeTextPaint.measureText("节"))
                val tw = maxOf(timeTextPaint.measureText(start), timeTextPaint.measureText(end))
                val cx = timeW / 2f
                val x1 = cx - (cw + gap + tw) / 2f + cw / 2f     // 节列中心
                val x2 = x1 + cw / 2f + gap + tw / 2f             // 时间列中心
                canvas.drawText("${s + 1}", x1, top + cellH * 0.40f, timeTextPaint)
                canvas.drawText("节", x1, top + cellH * 0.64f, timeTextPaint)
                canvas.drawText(start, x2, top + cellH * 0.36f, timeTextPaint)
                canvas.drawText(end, x2, top + cellH * 0.70f, timeTextPaint)
            } else {
                val label = if (time.isNotEmpty()) "${s + 1}节\n$time" else "${s + 1}节"
                drawMultiline(canvas, label, timeW / 2f, top + cellH / 2f, timeTextPaint, dp(12f))
            }
        }
        // 底边 + 左边线
        canvas.drawLine(timeW, height.toFloat(), width.toFloat(), height.toFloat(), gridPaint)
        canvas.drawLine(timeW, headerH + headerGap, timeW, height.toFloat(), gridPaint)

        // 被隐藏的周末列：若当周该列有课，在相应边界画绿线提示
        if (!showSat && d.lessons.any { it.dayOfWeek == 6 }) {
            val x = timeW + 5 * dayW   // 周五右
            canvas.drawLine(x, headerH + headerGap, x, height.toFloat(), weekendLinePaint)
        }
        if (!showSun && d.lessons.any { it.dayOfWeek == 7 }) {
            val x = timeW + days.size * dayW   // 显示区最右（周日原在最末列）
            canvas.drawLine(x, headerH + headerGap, x, height.toFloat(), weekendLinePaint)
        }

        // 课程块（仅主屏收集点击区域）
        if (collectRects) lessonRects.clear()
        for (lesson in d.lessons) {
            val dayCol = dayIndex[lesson.dayOfWeek] ?: continue   // 隐藏的天不在布局中
            val top = headerH + headerGap + (lesson.beginSection - 1) * cellH
            val bottom = top + lesson.length * cellH - dp(3f)
            val left = timeW + dayCol * dayW + dp(2f)
            val right = left + dayW - dp(4f)
            if (bottom <= top || right <= left) continue
            val rect = RectF(left, top, right, bottom)
            if (collectRects) lessonRects.add(rect to lesson)
            canvas.drawRoundRect(rect, corner, corner,
                Paint(courseFill).apply { color = courseColor(lesson) })
            // 变更强调：边框变橙
            if (keyOf(lesson) in changeKeys) {
                canvas.drawRoundRect(rect, corner, corner, changePaint)
            }
            // 显示名替换（单节优先，其次全局）
            val key = keyOf(lesson)
            val displayName = oneName[key] ?: nameMap[lesson.course] ?: lesson.course
            val displayLoc = oneLoc[key] ?: locMap[lesson.location] ?: lesson.location
            // 课程名 + 教室：按整块高度布局，统一字号，超出省略
            val cx = (left + right) / 2f
            val blockH = lesson.length * cellH
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

    private fun drawHeader(canvas: Canvas, dayW: Float, days: List<String>, isThisWeek: Boolean) {
        for (i in days.indices) {
            val left = timeW + i * dayW
            val right = left + dayW
            // 黄色高亮仅标记"本周(当前查看周=真实本周)的当天"，查看其他周不标
            val isToday = (i + 1) == todayDay && isThisWeek
            val bg = when {
                isToday -> headerTodayFill          // 当天保持黄色
                isThisWeek -> headerWeekendFill     // 查看本周：表头行浅绿
                else -> headerNormalFill
            }
            canvas.drawRect(left, 0f, right, headerH, bg)
            // 今日标记
            if (isToday) headerPaint.color = Color.WHITE
            val cx = (left + right) / 2f
            // 两行：上行周几、下行日期；表头太矮或开关关闭时保持单行居中
            if (showHeaderDate && headerH >= dp(34f)) {
                drawTextCenter(canvas, days[i], cx, headerH * 0.36f, headerPaint)
                val dt = dayDates[i + 1]
                if (dt != null) drawTextCenter(canvas, dt, cx, headerH * 0.76f, dateHeaderPaint)
            } else {
                drawTextCenter(canvas, days[i], cx, headerH / 2f, headerPaint)
            }
        }
        canvas.drawRect(0f, 0f, timeW, headerH, if (isThisWeek) headerWeekendFill else headerNormalFill)
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
