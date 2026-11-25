package com.example.scheduleapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 统一身份认证登录页。
 *
 * 学校教务系统要求先经 CAS 统一认证（i.wzut.edu.cn/cas/login），认证通过后回跳
 * jwxt.wzut.edu.cn/sso/ddlogin 建立教务系统会话。本页用内嵌 WebView 走完这一步，
 * 再把 jwxt 域的 Cookie 交给数据层复用（不保存学号密码）。
 */
class LoginWebActivity : Activity() {

    companion object {
        /** 统一认证入口；service 指向教务系统 CAS 回调。 */
        const val CAS_URL =
            "https://i.wzut.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.wzut.edu.cn%2Fsso%2Fddlogin"
        private const val JWXT_HOST = "jwxt.wzut.edu.cn"
        private const val TAG = "LoginWeb"
    }

    private val prefs by lazy { getSharedPreferences("schedule", MODE_PRIVATE) }

    private lateinit var web: WebView
    private var verifying = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        bar.addView(TextView(this).apply {
            text = "登录教务系统（统一身份认证）"
            textSize = 15f
            setTextColor(Color.parseColor("#17365D"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.parseColor("#3B6EB5"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        })
        root.addView(bar)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                checkUrl(request.url.toString())
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                checkUrl(url)
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                checkUrl(url)
            }
        }

        web.loadUrl(CAS_URL)
    }

    /** 页面进入教务系统域且拿到 JSESSIONID 后，验证会话并收尾。 */
    private fun checkUrl(url: String) {
        if (done) return
        val u = url.lowercase()
        if (!u.contains(JWXT_HOST) || u.contains("/cas/")) return
        val cookie = CookieManager.getInstance().getCookie("https://$JWXT_HOST") ?: return
        if (!cookie.contains("JSESSIONID")) return
        verifyAndFinish(cookie)
    }

    private fun verifyAndFinish(cookie: String) {
        if (verifying || done) return
        verifying = true
        Thread {
            val ok = try { JwxtApi.checkCookie(cookie) } catch (e: Exception) { false }
            runOnUiThread {
                verifying = false
                if (ok && !done) {
                    JwxtApi.saveCookie(prefs, cookie)
                    done = true
                    Log.d(TAG, "login ok, cookie saved len=${cookie.length}")
                    setResult(RESULT_OK)
                    Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return
        }
        super.onBackPressed()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
