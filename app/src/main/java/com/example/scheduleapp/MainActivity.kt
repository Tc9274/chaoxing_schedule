package com.example.scheduleapp

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    companion object {
        private const val REQ_LOGIN = 1001
    }

    private lateinit var titleView: TextView
    private lateinit var weekLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var scheduleView: ScheduleView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }
    private var infoBase = ""
    private var viewWeek = 0
    private var lastShownWeek = 0
    private var maxWeek = 0
    private val weekUpdateMs = HashMap<Int, Long>()   // 各周的刷新时间（状态行按当前查看周显示）
    private var pendingReturnWeek = 0   // 点击周数回本周时的目标周（>0 表示正在滑动中）
    private var currentData: ScheduleData? = null
    private var lastSeenDailyRun = 0L   // 主界面最后一次识别到的后台自动刷新

    // 定时刷新完成后，若 App 在前台，立即重读当前周显示（不必等滑动/回前台）
    private val dailyDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dr = prefs.getLong("last_daily_run", 0L)
            lastSeenDailyRun = dr
            if (viewWeek >= 1 && showCachedWeek(viewWeek)) updateStatus()
            ScheduleWidgetProvider.updateAll(this@MainActivity)
        }
    }

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
        weekLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF2C7BB6.toInt())
            typeface = Typeface.DEFAULT_BOLD
            // 点击蓝色周数回到真实本周（滑动动画切回）
            setOnClickListener {
                val rw = realWeek().takeIf { it > 0 } ?: viewWeek
                if (rw != viewWeek) {
                    pendingReturnWeek = rw
                    scheduleView.slideTo(rw)
                }
            }
        }
        val wlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        wlp.setMargins(0, 0, dp(8), 0)
        row1.addView(weekLabel, wlp)
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
        // 点空白格：添加自定义课程
        scheduleView.onEmptyClick = { day, sec -> showAddLessonDialog(day, sec, null) }
        scheduleView.customColor = { name -> prefs.getInt("courseColor_$name", 0) }
        scheduleView.oneColor = { key -> prefs.getInt("oneColor_$key", 0) }
        scheduleView.onWeekChanged = { week ->
            if (week != viewWeek) {
                viewWeek = week
                prefs.edit().putInt("last_view_week", week).apply()
                updateStatus()
                if (!showCachedWeek(week)) fetchWeek(week)
            }
            // 正在回本周的滑动中：未到目标周则延后到下一帧再滑一步（避免在动画回调里同步重启动画而闪换）
            if (pendingReturnWeek in 1..9999) {
                if (viewWeek == pendingReturnWeek) pendingReturnWeek = 0
                else handler.post { scheduleView.slideTo(pendingReturnWeek) }
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

        // 调度每天后台刷新，并尝试申请精确闹钟权限
        // 旧版默认 08:00 → 一次性迁到 00:00（用户手动改过的时间保留）
        if (!prefs.getBoolean("daily_time_migrated_zero", false)) {
            val oldT = prefs.getString("daily_refresh_time", null)
            if (oldT == null || oldT == "08:00") {
                prefs.edit().putString("daily_refresh_time", "00:00").apply()
            }
            prefs.edit().putBoolean("daily_time_migrated_zero", true).apply()
        }
        DailyRefreshReceiver.scheduleDaily(this)
        ensureExactAlarmPermission()
        lastSeenDailyRun = prefs.getLong("last_daily_run", 0L)

        // 无账号且无登录态时弹窗，登录后加载数据
        if (!JwxtApi.hasAccount(prefs) && !JwxtApi.hasLogin(prefs)) showLoginDialog() else initData()

        // 监听"定时刷新完成"，前台时立即重读当前周显示
        registerReceiver(dailyDoneReceiver,
            IntentFilter(DailyRefreshReceiver.ACTION_DAILY_DONE))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dailyDoneReceiver) } catch (e: Exception) { }
    }

    /** 未登录时的账号弹窗：输入学号密码后后台静默登录，不弹网页。 */
    private fun showLoginDialog() {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "登录教务系统"
            textSize = 17f
            setTextColor(0xFF17365D.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "输入学号和密码，App 会自动完成统一身份认证并获取课表。账号只保存在本机。"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, dp(12))
        })
        val inputUser = EditText(this).apply {
            hint = "学号"
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            setHintTextColor(0xFF999999.toInt())
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(inputUser, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val inputPass = EditText(this).apply {
            hint = "密码"
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            setHintTextColor(0xFF999999.toInt())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val eye = EyeView(this).apply {
            onToggle = { open ->
                inputPass.inputType = if (open)
                    android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                inputPass.setSelection(inputPass.text.length)
            }
        }
        passRow.addView(inputPass, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        passRow.addView(eye, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(passRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(14), 0, 0)
        }
        // 登录进度：转圈 + “正在登录…”（登录成功前一直显示）
        val loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(14), 0, 0)
        }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF3B6EB5.toInt())
        }
        loadingRow.addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)))
        val loadingText = TextView(this).apply {
            text = "正在登录并获取课表…"
            textSize = 13f
            setTextColor(0xFF3B6EB5.toInt())
            setPadding(dp(10), 0, 0, 0)
        }
        loadingRow.addView(loadingText)
        root.addView(loadingRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lateinit var loginBtn: TextView
        loginBtn = colorButton("登录并获取课表", 0xFF3B6EB5.toInt()) {
            val u = inputUser.text.toString().trim()
            val p = inputPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "请填写学号和密码", Toast.LENGTH_SHORT).show()
                return@colorButton
            }
            JwxtApi.saveAccount(prefs, u, p)
            // 切到“登录中”：禁用输入、隐藏按钮、显示转圈
            loginBtn.visibility = View.GONE
            inputUser.isEnabled = false
            inputPass.isEnabled = false
            eye.visibility = View.GONE
            spinner.visibility = View.VISIBLE
            loadingText.setTextColor(0xFF3B6EB5.toInt())
            loadingText.text = "正在登录并获取课表…"
            loadingRow.visibility = View.VISIBLE
            fetchAllWeeksInitial { ok, err ->
                if (ok) {
                    dlg.dismiss()
                } else {
                    loginBtn.visibility = View.VISIBLE
                    inputUser.isEnabled = true
                    inputPass.isEnabled = true
                    eye.visibility = View.VISIBLE
                    spinner.visibility = View.GONE
                    loadingText.setTextColor(0xFFD32F2F.toInt())
                    loadingText.text = "登录失败：${err ?: "网络错误"}"
                }
            }
        }
        root.addView(loginBtn, btnLp)
        root.addView(TextView(this).apply {
            text = "登录不上？改用网页登录"
            textSize = 12f
            setTextColor(0xFF3B6EB5.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                dlg.dismiss()
                startActivityForResult(Intent(this@MainActivity, LoginWebActivity::class.java), REQ_LOGIN)
            }
        })
        dlg.setContentView(root)
        dlg.setOnDismissListener {
            if (!JwxtApi.hasAccount(prefs) && !JwxtApi.hasLogin(prefs)) finish()
        }
        dlg.setCancelable(false)
        dlg.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN) {
            if (resultCode == RESULT_OK) initData() else finish()
        }
    }

    /** 有链接后的加载流程：有缓存直接显示真实本周，否则首次全量获取。 */
    private fun initData() {
        val hasAny = prefs.all.keys.any { it.startsWith("weekCache_") }
        if (hasAny) {
            ensureRealWeek()
            val rw = realWeek().takeIf { it > 0 } ?: getCachedViewWeek()
            showCachedWeek(rw)
            startTicker()
            // 每次进入主页都"激活一次回本周"：若显示的还不是真实本周，滑动切回（复用点击"本周"按钮逻辑）
            handler.post {
                val r = realWeek().takeIf { it > 0 } ?: return@post
                if (r != viewWeek) {
                    pendingReturnWeek = r
                    scheduleView.slideTo(r)
                }
            }
        } else {
            fetchAllWeeksInitial()
        }
    }

    /** 校准真实本周：本地有整学期数据就按当前口径重算并重建周缓存，否则退回读缓存里的 currentWeek。 */
    private fun ensureRealWeek() {
        if (JwxtApi.recalcWeeks(prefs) != null) return
        var bestKey: String? = null
        var bestUpd = -1L
        for (k in prefs.all.keys) {
            if (k.startsWith("weekCache_")) {
                val w = k.removePrefix("weekCache_").toIntOrNull() ?: continue
                val up = prefs.getLong("weekUpdate_$w", 0)
                if (up > bestUpd) { bestUpd = up; bestKey = k }
            }
        }
        val raw = bestKey?.let { prefs.getString(it, null) } ?: return
        try {
            val w = ScheduleApi.parse(raw).currentWeek
            if (w > 0 && w != prefs.getInt("real_week", 0)) {
                prefs.edit().putInt("real_week", w).apply()
            }
        } catch (e: Exception) { }
    }

    /** 首次启动：登录教务系统，一次拉取整学期并按周缓存。onDone 用于登录弹窗等待结果。 */
    private fun fetchAllWeeksInitial(onDone: ((Boolean, String?) -> Unit)? = null) {
        statusView.text = "首次获取课表…"
        Thread {
            try {
                val (cur, _) = JwxtApi.refreshAll(prefs)
                val week = cur.takeIf { it > 0 } ?: getCachedViewWeek()
                prefs.edit().putInt("last_view_week", week).putInt("real_week", cur).apply()
                weekUpdateMs[week] = System.currentTimeMillis()
                prefs.edit().putLong("weekUpdate_$week", weekUpdateMs[week]!!).apply()
                handler.post {
                    showCachedWeek(week)
                    startTicker()
                    Toast.makeText(this, "首次课表获取完成", Toast.LENGTH_SHORT).show()
                    onDone?.invoke(true, null)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "网络错误"
                handler.post {
                    if (onDone != null) { onDone(false, msg); return@post }
                    if (isLoginError(msg)) { goLogin(); return@post }
                    statusView.text = "获取失败"
                    Toast.makeText(this, "获取课表失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 是否是登录态失效类错误。 */
    private fun isLoginError(msg: String?): Boolean =
        msg != null && (msg.contains("登录已过期") || msg.contains("请先登录"))

    private fun goLogin() {
        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
        startActivityForResult(Intent(this, LoginWebActivity::class.java), REQ_LOGIN)
    }

    /**
     * 普通刷新：只重拉课表并重建各周缓存，不拉周次表（本地没有周次表时才补拉一次）。
     * 需要连周次一起更新用设置页的“完全更新”。
     */
    private fun refresh() {
        val week = viewWeek.takeIf { it > 0 } ?: getCachedViewWeek()
        statusView.text = "正在刷新课表…"
        Thread {
            try {
                val (cur, maxW) = JwxtApi.refreshKbOnly(prefs)
                val showW = if (week in 1..maxW) week else cur.coerceIn(1, maxW)
                val raw = prefs.getString("weekCache_$showW", null)
                    ?: JwxtApi.fetchWeek(prefs, showW, false)
                val data = ScheduleApi.parse(raw)
                prefs.edit()
                    .putInt("last_view_week", showW)
                    .putInt("real_week", data.currentWeek)
                    .apply()
                weekUpdateMs[showW] = System.currentTimeMillis()
                viewWeek = showW
                lastShownWeek = showW
                handler.post {
                    renderWeek(data, showW)
                    startTicker()
                    ScheduleWidgetProvider.updateAll(this)   // App 内刷新后同步桌面组件
                    Toast.makeText(this,
                        "课表已更新 · 本周第${data.currentWeek}周",
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    if (isLoginError(e.message)) { goLogin(); return@post }
                    statusView.text = "加载失败，请检查网络"
                    Toast.makeText(this, "获取课表失败：${e.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 切周无缓存时：请求该周并缓存。 */
    private fun fetchWeek(week: Int) {
        statusView.text = "加载第${week}周…"
        Thread {
            try {
                val raw = JwxtApi.fetchWeek(prefs, week, false)
                val data = ScheduleApi.parse(raw)
                prefs.edit().putString("weekCache_$week", raw).putInt("last_view_week", week)
                    .putInt("real_week", data.currentWeek).apply()
                handler.post { renderWeek(data, week); ScheduleWidgetProvider.updateAll(this) }
            } catch (e: Exception) {
                handler.post {
                    if (isLoginError(e.message)) { goLogin(); return@post }
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
        renderWeek(d, week)
        return true
    }

    /** 渲染某周课表。 */
    private fun renderWeek(data: ScheduleData, week: Int) {
        // 真实本周恒定（用最近抓取接口确认的值），避免旧缓存周解析出不一致的"本周"
        val realW = realWeek()
        val d0 = if (realW > 0 && data.currentWeek != realW) data.copy(currentWeek = realW) else data
        // 合并手动添加的课程（整学期固定 / 仅某一周）
        val d = CustomLessons.merge(d0, week, prefs)
        currentData = d
        scheduleView.setWeekStarts(JwxtApi.weekStartMap(prefs))
        scheduleView.setData(d, todayDayOfWeek(), week)
        scheduleView.setAdjacentData(parseCached(week - 1), parseCached(week + 1))
        applyDisplayStyle()
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        reloadOverlap()
        val campus = if (d.campus.isNotEmpty()) " · ${d.campus}" else ""
        infoBase = "${d.schoolYear}学年第${d.semester}学期$campus"
        viewWeek = week
        lastShownWeek = week
        maxWeek = d.maxWeek
        weekLabel.text = "本周：第${d.currentWeek}周"   // 蓝色周数=真实本周，恒定不随切周变
        updateStatus()
    }

    /** 真实本周：优先用最近抓取接口确认的恒定值，避免旧缓存周解析出不一致。 */
    private fun realWeek(): Int {
        prefs.getInt("real_week", 0).takeIf { it > 0 }?.let { return it }
        return currentData?.currentWeek ?: 0
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

    /** 顶部信息行：学期 + 当前查看周 + 该周距上次刷新时间。 */
    private fun updateStatus() {
        val week = "第${viewWeek}周/共${maxWeek}周"
        val ms = prefs.getLong("weekUpdate_$viewWeek", 0)
        statusView.text = if (ms > 0)
            "$infoBase · $week · 更新于 ${relTime(ms)}"
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

    /** 从设置读取显示参数并应用到课表。字号/表头高为 0 时用默认；格子高 0=自动填满视口。 */
    private fun applyDisplayStyle() {
        fun dflt(k: String, fb: Float): Float {
            val v = prefs.getFloat(k, 0f)
            return if (v > 0) v else fb
        }
        val fc = dflt("font_course", dp(9).toFloat())
        val fl = dflt("font_loc", dp(8).toFloat())
        val fh = dflt("font_header", dp(11).toFloat())
        val hh = dflt("header_h", dp(48).toFloat())
        // 格子高：0=自动填满视口；未设置时默认 36dp
        val cell = prefs.getFloat("cell_h", 0f)
        val cellApply = if (prefs.contains("cell_h")) cell else dp(36).toFloat()
        scheduleView.setDisplayStyle(
            fc, fl, fh, hh, cellApply,
            prefs.getFloat("name_y", 0.27f),
            prefs.getFloat("loc_y", 0.60f)
        )
        scheduleView.setAnimDurations(
            prefs.getInt("anim_switch", 300),
            prefs.getInt("anim_back", 300),
            prefs.getInt("anim_cancel", 300)
        )
        scheduleView.setShowDays(
            prefs.getBoolean("show_sat", true),
            prefs.getBoolean("show_sun", true))
        scheduleView.setShowHeaderDate(prefs.getBoolean("show_header_date", true))
        scheduleView.setTimeWidth(prefs.getFloat("time_w", dp(58).toFloat()))
        scheduleView.setTimeTwoLine(prefs.getBoolean("time_two_line", true))
        scheduleView.setTextColor(when (prefs.getInt("text_color", 1)) {
            0 -> 0xFF000000.toInt()   // 黑
            2 -> 0xFF9E9E9E.toInt()   // 灰
            else -> 0xFFFFFFFF.toInt() // 白（默认）
        })
    }

    override fun onResume() {
        super.onResume()
        // 后台定时刷新完成后，回到前台自动重读当前周缓存，反映最新数据
        val dr = prefs.getLong("last_daily_run", 0L)
        if (dr > 0 && dr != lastSeenDailyRun) {
            lastSeenDailyRun = dr
            if (viewWeek >= 1 && showCachedWeek(viewWeek)) updateStatus()
        }
        // 每次回到前台（含从后台恢复）都"激活一次回本周"，与冷启动行为一致
        ensureRealWeek()
        val r = realWeek().takeIf { it > 0 }
        if (r != null && r != viewWeek) {
            pendingReturnWeek = r
            scheduleView.slideTo(r)
        }
        if (currentData != null) applyDisplayStyle()
        updateStatus()   // 刷新状态行（含"更新于"时间）
    }

    /** 读取某周的缓存数据（供相邻屏滑动显示）。 */
    private fun parseCached(week: Int): ScheduleData? {
        if (week < 1) return null
        val raw = prefs.getString("weekCache_$week", null) ?: return null
        val d = try { ScheduleApi.parse(raw) } catch (e: Exception) { null }
        return d?.let { CustomLessons.merge(it, week, prefs) }
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

    /** 显示名设置变更后刷新渲染。 */
    private fun refreshDisplay() {
        if (currentData == null) return
        scheduleView.setDisplayMaps(readStringMap("nameMap_"), readStringMap("locMap_"),
            readStringMap("oneName_"), readStringMap("oneLoc_"))
        reloadOverlap()
        scheduleView.invalidate()
    }

    /** 重叠显示设置（前后顺序 / 只显示哪节）改动后重新注入视图。 */
    private fun reloadOverlap() {
        scheduleView.setOverlapPrefs(readStringMap("overlapFront_"), readStringMap("overlapMode_"))
    }

    /** 点空白格添加课程；editing 非空时为编辑已有自定义课程。 */
    private fun showAddLessonDialog(day: Int, section: Int, editing: CustomLesson?) {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val weekNames = arrayOf("", "一", "二", "三", "四", "五", "六", "日")
        val dayName = weekNames[day.coerceIn(1, 7)]
        root.addView(TextView(this).apply {
            text = if (editing != null) "编辑课程" else "添加课程"
            textSize = 16f
            setTextColor(0xFF17365D.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "周$dayName · 点在第 $section 节（起始节和节数可调整）"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(4), 0, dp(6))
        })
        root.addView(fieldLabel("课程名（必填）"))
        val nameInput = fieldInput(editing?.course ?: "")
        nameInput.hint = "如：自习、社团活动"
        root.addView(nameInput, fieldInputLp())
        root.addView(fieldLabel("老师（可不填）"))
        val teacherInput = fieldInput(editing?.teacher ?: "")
        teacherInput.hint = "如：张老师"
        root.addView(teacherInput, fieldInputLp())
        root.addView(fieldLabel("地点（可不填）"))
        val locInput = fieldInput(editing?.location ?: "")
        locInput.hint = "如：图书馆 3 楼"
        root.addView(locInput, fieldInputLp())

        val sectionCount = currentData?.sectionTimes?.size?.coerceAtLeast(1) ?: 12
        root.addView(fieldLabel("起始节"))
        val secSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, (1..sectionCount).map { "$it" })
            setSelection((editing?.begin ?: section).coerceIn(1, sectionCount) - 1)
        }
        root.addView(secSpinner, fieldInputLp())
        root.addView(fieldLabel("节数"))
        val lenSpinner = android.widget.Spinner(this)
        root.addView(lenSpinner, fieldInputLp())
        var adjusting = false
        fun bindLen(keep: Int) {
            val begin = secSpinner.selectedItemPosition + 1
            val maxLen = (sectionCount - begin + 1).coerceAtLeast(1)
            lenSpinner.adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, (1..maxLen).map { "$it 节" })
            lenSpinner.setSelection(keep.coerceIn(1, maxLen) - 1)
        }
        bindLen(editing?.length ?: 1)
        secSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (adjusting) return
                val keep = if (editing != null && pos + 1 == editing.begin) editing.length
                else lenSpinner.selectedItemPosition + 1
                adjusting = true
                bindLen(keep)
                adjusting = false
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        }

        // 上课周次：点开弹窗按周勾选（单周/双周/全选/本周四种快捷方式）
        // 总周数取三者最大：课表数据、周次表（学校口径 20 周）、当前周
        val tableMaxWeek = JwxtApi.weekStartMap(prefs).keys.maxOrNull() ?: 0
        val maxWeek = maxOf(currentData?.maxWeek ?: viewWeek, tableMaxWeek, viewWeek).coerceAtLeast(1)
        val weekNamesHint = TextView(this).apply { textSize = 12f; setTextColor(0xFF777777.toInt()) }
        // 新增默认“本周”；编辑已有课沿用其周次（旧的“每周”数据展开成全选）
        var pickedWeeks: List<Int> = editing?.let { it.weeks.ifEmpty { (1..maxWeek).toList() } }
            ?: listOf(viewWeek.coerceIn(1, maxWeek))
        fun refreshWeekRow() {
            weekNamesHint.text = "上课周次：" + CustomLessons.weeksText(pickedWeeks) + "  （点击修改）"
        }
        refreshWeekRow()
        val weekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFFF2F5F8.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        weekRow.addView(weekNamesHint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        weekRow.addView(TextView(this).apply {
            text = "修改"
            textSize = 12f
            setTextColor(0xFF3B6EB5.toInt())
            setPadding(dp(8), 0, 0, 0)
        })
        weekRow.setOnClickListener {
            // 按当前选的起始节/节数算冲突周，换个位置再点会重新算
            showWeekPickerDialog(
                maxWeek, viewWeek, pickedWeeks,
                day, secSpinner.selectedItemPosition + 1, lenSpinner.selectedItemPosition + 1,
                editing?.id
            ) { list ->
                pickedWeeks = list
                refreshWeekRow()
            }
        }
        root.addView(fieldLabel("上课周次"))
        root.addView(weekRow, fieldInputLp())

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addBtn(t: String, color: Int, act: () -> Unit) {
            btnRow.addView(colorButton(t, color, act),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 0, dp(6), 0) })
        }
        addBtn("保存", 0xFF3B6EB5.toInt()) {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请填写课程名", Toast.LENGTH_SHORT).show()
                return@addBtn
            }
            val c = CustomLesson(
                id = editing?.id ?: System.currentTimeMillis(),
                course = name,
                day = day.coerceIn(1, 7),
                begin = secSpinner.selectedItemPosition + 1,
                length = lenSpinner.selectedItemPosition + 1,
                location = locInput.text.toString().trim(),
                teacher = teacherInput.text.toString().trim(),
                weeks = pickedWeeks
            )
            CustomLessons.upsert(prefs, c)
            afterCustomChanged("已保存：周$dayName 第 ${c.begin} 节 · " +
                    CustomLessons.weeksText(c.weeks))
            dlg.dismiss()
        }
        if (editing != null) {
            addBtn("删除", 0xFFE64A19.toInt()) {
                CustomLessons.remove(prefs, editing.id)
                afterCustomChanged("已删除这门自定义课程")
                dlg.dismiss()
            }
        }
        btnRow.addView(colorButton("取消", 0xFF9E9E9E.toInt()) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnLp.setMargins(0, dp(12), 0, 0)
        root.addView(btnRow, btnLp)

        val scroll = ScrollView(this)
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(0xAAFFFFFF.toInt())
            cornerRadius = dp(20).toFloat()
        })
        dlg.setContentView(scroll)
        dlg.show()
    }

    /** 与这一节（同一天、节次相交）重叠的其他课。 */
    private fun overlapsOf(lesson: Lesson): List<Lesson> {
        val all = currentData?.lessons ?: return emptyList()
        val myKey = scheduleView.keyOf(lesson)
        return all.filter {
            scheduleView.keyOf(it) != myKey && it.dayOfWeek == lesson.dayOfWeek &&
                ScheduleView.rangesOverlap(lesson.beginSection, lesson.length, it.beginSection, it.length)
        }
    }

    /**
     * 某一节（day, begin, length）在哪些周会和已有课重叠。
     * 已有课 = 各周缓存里学校的课 + 其他自定义课（编辑时排除正在编辑的那条）。
     */
    private fun conflictWeeksFor(day: Int, begin: Int, length: Int, excludeId: Long?, maxWeek: Int): Set<Int> {
        val out = HashSet<Int>()
        val customs = CustomLessons.load(prefs).filter { it.id != excludeId }
        for (w in 1..maxWeek) {
            var hit = false
            val raw = prefs.getString("weekCache_$w", null)
            if (raw != null) {
                val d = try { ScheduleApi.parse(raw) } catch (e: Exception) { null }
                if (d != null) hit = d.lessons.any {
                    it.dayOfWeek == day && ScheduleView.rangesOverlap(begin, length, it.beginSection, it.length)
                }
            }
            if (!hit) hit = customs.any {
                it.day == day && (it.weeks.isEmpty() || it.weeks.contains(w)) &&
                    ScheduleView.rangesOverlap(begin, length, it.begin, it.length)
            }
            if (hit) out.add(w)
        }
        return out
    }

    /** 弹窗里每一个重叠对象一行：说明 + 交换前后 / 只看本课 / 都显示（改动立即反映到后面的课表）。 */
    private fun overlapCard(lesson: Lesson, other: Lesson): View {
        val pair = ScheduleView.pairId(scheduleView.keyOf(lesson), scheduleView.keyOf(other))
        val myKey = scheduleView.keyOf(lesson)
        val otherKey = scheduleView.keyOf(other)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFFFFF3F2.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFFFF7B72.toInt())
            }
        }
        card.addView(TextView(this).apply {
            text = "与「${currentDisplayName(other)}」 第${other.beginSection}~${other.beginSection + other.length - 1}节 重叠"
            textSize = 12f
            setTextColor(0xFF555555.toInt())
        })
        val stateTv = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFD9363E.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        fun refreshState() {
            val front = prefs.getString("overlapFront_$pair", null)
                ?: ScheduleView.defaultFrontKey(lesson, other)
            val mode = prefs.getString("overlapMode_$pair", null)
            stateTv.text = when {
                mode == myKey -> "当前：只显示本课"
                mode == otherKey -> "当前：只显示对方"
                front == myKey -> "当前：本课在前（淡）· 对方在后（深）"
                else -> "当前：对方在前（淡）· 本课在后（深）"
            }
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addBtn(t: String, color: Int, act: () -> Unit) {
            btnRow.addView(colorButton(t, color, act),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, dp(6), dp(6), 0) })
        }
        addBtn("交换前后", 0xFF3B6EB5.toInt()) {
            val cur = prefs.getString("overlapFront_$pair", null)
                ?: ScheduleView.defaultFrontKey(lesson, other)
            val next = if (cur == myKey) otherKey else myKey
            prefs.edit().putString("overlapFront_$pair", next).apply()
            reloadOverlap(); refreshState()
        }
        addBtn("只看本课", 0xFFE64A19.toInt()) {
            prefs.edit().putString("overlapMode_$pair", myKey).apply()
            reloadOverlap(); refreshState()
        }
        addBtn("都显示", 0xFF7E57C2.toInt()) {
            prefs.edit().remove("overlapMode_$pair").apply()
            reloadOverlap(); refreshState()
        }
        refreshState()
        card.addView(stateTv)
        card.addView(btnRow)
        return card
    }

    /**
     * 上课周次选择弹窗（仿正方课表）：上方 5 列周次格子，可点选、可按住滑动连续多选 / 连续取消；
     * 下方 单周 / 双周 / 全选 / 本周 四个快捷单选，默认落在“本周”。
     */
    private fun showWeekPickerDialog(
        maxWeek: Int, currentWeek: Int, initial: List<Int>,
        candidateDay: Int, candidateBegin: Int, candidateLength: Int, excludeId: Long?,
        onDone: (List<Int>) -> Unit
    ) {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }

        // 顶部：取消 / 标题 / 确定
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancelTv = TextView(this).apply {
            text = "取消"; textSize = 15f; setTextColor(0xFF3C9BFA.toInt())
            setPadding(0, dp(6), dp(6), dp(6))
        }
        val titleTv = TextView(this).apply {
            text = "上课周次"; textSize = 17f; setTextColor(0xFF222222.toInt())
            gravity = Gravity.CENTER
        }
        val okTv = TextView(this).apply {
            text = "确定"; textSize = 15f; setTextColor(0xFF3C9BFA.toInt())
            setPadding(dp(6), dp(6), 0, dp(6))
        }
        head.addView(cancelTv)
        head.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(okTv)
        root.addView(head)

        val startWeeks = initial.ifEmpty { (1..maxWeek).toList() }
        val picker = WeekPickerView(this).apply {
            this.maxWeek = maxWeek
            setWeeks(startWeeks)
        }
        val pickerLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        pickerLp.setMargins(0, dp(12), 0, dp(8))
        root.addView(picker, pickerLp)

        Thread {
            val set = conflictWeeksFor(candidateDay, candidateBegin, candidateLength, excludeId, maxWeek)
            handler.post { picker.conflictWeeks = set }
        }.start()

        val rg = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // RadioGroup 依赖子 View 的 id 管理选中状态，无 id 时点击无效
        fun radio(t: String) = RadioButton(this).apply {
            text = t; textSize = 13f; id = View.generateViewId()
        }
        val rbOdd = radio("单周")
        val rbEven = radio("双周")
        val rbAll = radio("全选")
        val rbCur = radio("本周")
        rg.addView(rbOdd); rg.addView(rbEven); rg.addView(rbAll); rg.addView(rbCur)
        root.addView(rg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, 0) })

        // 初始单选状态：按当前选中的集合反推是哪种快捷方式
        when {
            startWeeks.size == maxWeek -> rbAll.isChecked = true
            startWeeks == listOf(currentWeek) -> rbCur.isChecked = true
            startWeeks.containsAll((1..maxWeek).filter { it % 2 == 1 }) &&
                    !startWeeks.any { it % 2 == 0 } -> rbOdd.isChecked = true
            startWeeks.containsAll((1..maxWeek).filter { it % 2 == 0 }) &&
                    !startWeeks.any { it % 2 == 1 } -> rbEven.isChecked = true
        }

        // 手动改动格子 → 取消下方四选一
        picker.onChanged = { rg.clearCheck() }
        rg.setOnCheckedChangeListener { _, id ->
            val weeks = when (id) {
                rbOdd.id -> (1..maxWeek).filter { it % 2 == 1 }
                rbEven.id -> (1..maxWeek).filter { it % 2 == 0 }
                rbAll.id -> (1..maxWeek).toList()
                rbCur.id -> listOf(currentWeek)
                else -> null
            }
            if (weeks != null) picker.setWeeks(weeks)
        }

        cancelTv.setOnClickListener { dlg.dismiss() }
        okTv.setOnClickListener {
            val weeks = picker.weeks().filter { it in 1..maxWeek }
            if (weeks.isEmpty()) {
                Toast.makeText(this, "请至少选择一周", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onDone(weeks)
            dlg.dismiss()
        }

        dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(0xFFF7FBFF.toInt())
            cornerRadius = dp(16).toFloat()
        })
        dlg.setContentView(root)
        // 屏幕窄时弹窗铺满宽度，格子才够大
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    /** 自定义课程增删改后：重渲染当前周、同步桌面组件、提示。 */
    private fun afterCustomChanged(msg: String) {
        val w = viewWeek
        if (!showCachedWeek(w)) refreshDisplay()
        ScheduleWidgetProvider.updateAll(this)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 点击课程块弹出非全屏悬浮窗。 */
    // 弹窗可选课程颜色池（浅色系但色相差明显，互不接近）
    private val colorPalette = intArrayOf(
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
        val customId = CustomLessons.idOf(lesson)
        val weekText = if (customId != null) "自定义课程 · " + (CustomLessons.load(prefs)
            .firstOrNull { it.id == customId }?.let { CustomLessons.weeksText(it.weeks) } ?: "")
        else lesson.weeks
        // 详情：没填的行就不显示（位置/老师为空时整行省掉）
        val detailLines = ArrayList<String>()
        detailLines.add("星期${lesson.dayOfWeek}  第${lesson.beginSection}~${lesson.beginSection + lesson.length - 1}节")
        val locText = currentDisplayLoc(lesson).trim()
        if (locText.isNotEmpty()) detailLines.add("位置：$locText")
        val teacherText = lesson.teacher.trim()
        if (teacherText.isNotEmpty()) detailLines.add("老师：$teacherText")
        detailLines.add("周次：$weekText")
        root.addView(TextView(this).apply {
            text = detailLines.joinToString("\n")
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
        // 课程颜色选择（同一课程一个颜色，持久化）
        root.addView(fieldLabel("课程颜色"))
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val baseColor = prefs.getInt("courseColor_${lesson.course}",
            prefs.getInt("oneColor_${keyOf(lesson)}", 0))
        var pendingColor: Int? = null   // 选色暂存，点"保存"才应用
        val swatches = ArrayList<View>()
        // "随机"（为该课随机分配一个不与其他课重复的浅色）
        val resetSwatch = TextView(this).apply {
            text = "随机"
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFFEEEEEE.toInt())
                cornerRadius = dp(6).toFloat()
            }
        }
        fun refreshSwatches() {
            swatches.forEach { s ->
                val c = s.tag as Int
                (s.background as GradientDrawable).setStroke(
                    if (pendingColor == c || (pendingColor == null && baseColor == c)) dp(2) else 0,
                    0xFF222222.toInt())
            }
            (resetSwatch.background as GradientDrawable).setStroke(
                if (pendingColor == null && baseColor == 0) dp(2) else 0,
                0xFF222222.toInt())
        }
        colorPalette.forEach { c ->
            val swatch = View(this).apply {
                tag = c
                background = GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = dp(6).toFloat()
                }
            }
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
                .apply { setMargins(0, 0, dp(8), 0) }
            swatch.setOnClickListener { pendingColor = c; refreshSwatches() }
            swatches.add(swatch)
            colorRow.addView(swatch, lp)
        }
        resetSwatch.setOnClickListener {
            pendingColor = scheduleView.assignRandomColor(lesson.course)
            refreshSwatches()
        }
        colorRow.addView(resetSwatch,
            LinearLayout.LayoutParams(dp(44), dp(30)))
        val colorScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(colorRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(colorScroll)
        refreshSwatches()
        // 与别的课重叠时：红框 + 前后顺序 / 只显示哪节，都可在这里切换
        val others = overlapsOf(lesson)
        if (others.isNotEmpty()) {
            root.addView(fieldLabel("重叠课程（红框 · 可切换显示）"))
            for (o in others) {
                root.addView(overlapCard(lesson, o), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, dp(6)) })
            }
        }
        // 三个操作按钮放一行
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addAction(t: String, color: Int, act: () -> Unit) {
            btnRow.addView(colorButton(t, color, act),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 0, dp(6), 0) })
        }
        addAction("保存", 0xFF3B6EB5.toInt()) {
            saveDisplayRule(lesson, nameInput.text.toString().trim(),
                locInput.text.toString().trim(), rbAll.isChecked)
            if (pendingColor != null) {
                if (rbAll.isChecked) prefs.edit().putInt("courseColor_${lesson.course}", pendingColor!!).apply()
                else prefs.edit().putInt("oneColor_${keyOf(lesson)}", pendingColor!!).apply()
            }
            refreshDisplay()
            ScheduleWidgetProvider.updateAll(this)   // 改名/位置/颜色保存后同步组件
            dlg.dismiss()
        }
        // 手动添加的课程：在弹窗里提供编辑 / 删除
        if (customId != null) {
            addAction("编辑/删除", 0xFF7E57C2.toInt()) {
                dlg.dismiss()
                CustomLessons.load(prefs).firstOrNull { it.id == customId }?.let { c ->
                    showAddLessonDialog(c.day, c.begin, c)
                }
            }
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnLp.setMargins(0, dp(10), 0, 0)
        root.addView(btnRow, btnLp)
        // 弹窗圆角加大、背景半透明（能看到后面课表）
        dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(0xAAFFFFFF.toInt())
            cornerRadius = dp(20).toFloat()
        })
        // 内容可能变长（多了重叠控制），套一层 ScrollView 免得小屏放不下
        val sv = ScrollView(this).apply { addView(root) }
        dlg.setContentView(sv)
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
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE   // 圆角方形
                setColor(color)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }

    /** 读取用户填写的课表 uuid（未填写返回空串，启动时弹窗提示填写）。 */

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
