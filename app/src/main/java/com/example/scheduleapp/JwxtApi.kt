package com.example.scheduleapp

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Calendar
import javax.crypto.Cipher

/**
 * 温州理工学院正方教务系统（jwglxt）课表抓取。
 *
 * 流程：学号+密码（RSA 加密）登录 → 拉整学期课表 → 按 zcd 周次过滤成"某一周"的课表。
 * 输出统一转换为与超星同构的 JSON（result=1 / data.curriculum / data.lessonArray），
 * 因此现有缓存、ScheduleApi.parse、桌面组件全部可直接复用。
 */
object JwxtApi {

    const val BASE = "https://jwxt.wzut.edu.cn"
    const val HOME = "$BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
    private const val KW_URL = "$BASE/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
    /** 周次清单接口（N2154 “按周查询”）：返回每周 zs + 起止日期 rq，用于确定真实周次。 */
    private const val ZC_URL = "$BASE/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html"
    private const val ZC_PAGE = "$BASE/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N2154&layout=default"
    private const val LOGIN_PAGE = "$BASE/jwglxt/xtgl/login_slogin.html"
    private const val INDEX_URL = "$BASE/jwglxt/xtgl/index_initMenu.html"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    /**
     * 作息表接口（正方“上课时段”）：返回每节的 jcmc（节次）、qssj / jssj（起止时间）。
     * 需要 xnm、xqm、xqh_id（校区，取自课表返回的 xsxx.xqh1）。
     */
    private const val SJ_URL = "$BASE/jwglxt/jzgl/skxxMobile_cxRsdjc.html"

    /**
     * 节次默认作息（滨海校区实测口径，共 11 节）。联网拿到作息表后以接口数据为准，
     * 这里只作离线兜底（1 节对应下标 0）。
     */
    val DEFAULT_SECTION_TIMES = listOf(
        "08:40-09:20", "09:25-10:05", "10:25-11:05", "11:10-11:50",
        "13:30-14:10", "14:15-14:55", "15:15-15:55", "16:00-16:40",
        "18:30-19:10", "19:15-19:55", "20:00-20:40"
    )

    init {
        // 全局 Cookie 容器（网页登录态之外，作为补充）
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
    }

    // ---------- 登录态 ----------

    private const val TAG = "JwxtApi"

    /** 是否已保存教务系统登录态（来自内嵌网页统一认证登录）。 */
    fun hasLogin(prefs: SharedPreferences): Boolean =
        (prefs.getString("jwxt_cookie", "") ?: "").isNotEmpty()

    fun cookieOf(prefs: SharedPreferences): String =
        prefs.getString("jwxt_cookie", "") ?: ""

    /** 保存内嵌网页登录后拿到的 Cookie（含 JSESSIONID）。 */
    fun saveCookie(prefs: SharedPreferences, cookie: String) {
        prefs.edit().putString("jwxt_cookie", cookie).apply()
    }

    fun clearLogin(prefs: SharedPreferences) {
        prefs.edit().remove("jwxt_cookie").apply()
    }

    /** 用 Cookie 访问一次首页，判断登录态是否仍然有效。 */
    fun checkCookie(cookie: String): Boolean {
        if (cookie.isEmpty()) return false
        return try {
            val body = httpGet(INDEX_URL, cookie)
            val ok = body.isNotBlank() && !body.contains("login_slogin") && !body.contains("用户登录")
            Log.d(TAG, "checkCookie -> $ok len=${body.length}")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "checkCookie err ${e.message}")
            false
        }
    }

    // ---------- 统一认证静默登录 ----------

    /** 统一认证入口；service 指向教务系统 CAS 回调。 */
    const val CAS_URL =
        "https://i.wzut.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.wzut.edu.cn%2Fsso%2Fddlogin"

    /** 统一认证页写死的 1024 位 RSA 公钥（modulus，hex）。 */
    private const val CAS_MOD =
        "008aed7e057fe8f14c73550b0e6467b023616ddc8fa91846d2613cdb7f7621e3cada4cd5d812d627af6b87727ade4e26d26208b7326815941492b2204c3167ab2d53df1e3a2c9153bdb7c8c2e968df97a5e7e01cc410f92c4c2c2fba529b3ee988ebc1fca99ff5119e036d732c368acf8beba01aa2fdafa45b21e4de4928d0d403"

    /** 是否保存了学号密码（可静默登录）。 */
    fun hasAccount(prefs: SharedPreferences): Boolean =
        (prefs.getString("jw_user", "") ?: "").isNotEmpty() &&
                (prefs.getString("jw_pass_enc", "") ?: "").isNotEmpty()

    fun saveAccount(prefs: SharedPreferences, user: String, pass: String) {
        val ed = prefs.edit().putString("jw_user", user.trim()).remove("jwxt_cookie")
        if (pass.isNotEmpty()) ed.putString("jw_pass_enc", encryptSimple(pass))
        ed.apply()
    }

    private fun encryptSimple(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        for (i in b.indices) b[i] = (b[i].toInt() xor 0x5A).toByte()
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun decryptSimple(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            for (i in b.indices) b[i] = (b[i].toInt() xor 0x5A).toByte()
            String(b, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    /** 统一认证 RSA：jsbn 口径（明文按字符补齐 126、低位在前，输出定长 hex）。 */
    private fun casEncrypt(pass: String): String {
        val n = BigInteger(CAS_MOD, 16)
        val e = BigInteger("10001", 16)
        val arr = ByteArray(126)
        for (i in pass.indices) {
            if (i >= 126) break
            arr[i] = pass[i].code.toByte()
        }
        // jsbn 把第一个字符放在最低位：反转后按大端整数计算
        val m = BigInteger(1, arr.reversedArray())
        return m.modPow(e, n).toString(16).padStart(256, '0')
    }

    /** 极简 Cookie 罐：按 host 存 name=value，同名的后来覆盖。 */
    private class CookieJar {
        private val store = HashMap<String, LinkedHashMap<String, String>>()

        fun put(host: String, setCookies: List<String>?) {
            if (setCookies.isNullOrEmpty()) return
            val m = store.getOrPut(host) { LinkedHashMap() }
            for (raw in setCookies) {
                val kv = raw.split(";").firstOrNull()?.trim() ?: continue
                if (!kv.contains("=")) continue
                val name = kv.substringBefore("=").trim()
                if (name.isEmpty() || name.startsWith("_path")) continue
                m[name] = kv.substringAfter("=").trim()
            }
        }

        fun header(host: String): String =
            store[host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""
    }

    private class Resp(val code: Int, val location: String?, val body: String)

    private fun casRequest(url: String, method: String, form: String?, jar: CookieJar,
                           referer: String?): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            val host = URL(url).host
            jar.header(host).takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Cookie", it) }
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            jar.put(host, conn.headerFields["Set-Cookie"])
            val loc = conn.getHeaderField("Location")
            val body = if (code in 300..399) "" else try {
                (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) { "" }
            Log.d(TAG, "$method ${url.take(70)} -> $code loc=$loc")
            return Resp(code, loc, body)
        } finally { conn.disconnect() }
    }

    private fun absUrl(base: String, loc: String): String =
        if (loc.startsWith("http")) loc else URL(URL(base), loc).toString()

    /** 静默登录统一认证，返回教务系统 Cookie；失败抛 IOException（含页面提示语）。 */
    fun loginAuto(user: String, pass: String): String {
        val jar = CookieJar()
        val page = casRequest(CAS_URL, "GET", null, jar, null)
        if (page.code != 200) throw IOException("统一认证不可用（HTTP ${page.code}）")
        val exec = Regex("name=\"execution\" value=\"([^\"]*)\"")
            .find(page.body)?.groupValues?.get(1)
            ?: throw IOException("统一认证页面结构变化，请改用网页登录")
        val form = "username=${urlEnc(user)}&password=${casEncrypt(pass)}" +
                "&execution=${urlEnc(exec)}&_eventId=submit&loginType=1" +
                "&encrypted=true&sliderCaptchaProof="
        val post = casRequest(CAS_URL, "POST", form, jar, CAS_URL)
        if (post.code == 200) {
            val msg = Regex("<span[^>]*id=\"msg[0-9]*\"[^>]*>(.*?)</span>")
                .find(post.body)?.groupValues?.get(1)?.trim()
            throw IOException(if (msg.isNullOrEmpty()) "登录被拒绝" else msg)
        }
        var url = absUrl(CAS_URL, post.location ?: throw IOException("登录未返回票据"))
        for (i in 0 until 6) {
            val r = casRequest(url, "GET", null, jar, CAS_URL)
            if (r.code in 300..399) {
                url = absUrl(url, r.location ?: throw IOException("跳转异常"))
            } else break
        }
        val cookie = jar.header("jwxt.wzut.edu.cn")
        if (!cookie.contains("JSESSIONID")) throw IOException("未拿到教务系统登录态")
        Log.d(TAG, "auto login ok")
        return cookie
    }

    /** 取可用登录态：本地 Cookie 有效就用，否则用保存的账号密码静默重登。 */
    fun ensureLogin(prefs: SharedPreferences): String {
        val cookie = cookieOf(prefs)
        if (cookie.isNotEmpty() && checkCookie(cookie)) return cookie
        val user = prefs.getString("jw_user", "") ?: ""
        val pass = decryptSimple(prefs.getString("jw_pass_enc", "") ?: "")
        if (user.isEmpty() || pass.isEmpty()) throw IOException("请先填写学号密码")
        val fresh = loginAuto(user, pass)
        saveCookie(prefs, fresh)
        return fresh
    }

    /** 最近一次登录失败的具体原因，供界面提示。 */
    @Volatile var lastError: String = ""

    /** 学号密码登录，成功返回 true。 */
    fun login(user: String, pass: String): Boolean {
        lastError = ""
        val keyJson = try { httpGet("$BASE/jwglxt/xtgl/login_getPublicKey.html") } catch (e: Exception) { "" }
        if (keyJson.isEmpty()) throw IOException("无法连接教务系统")
        val jo = JSONObject(keyJson)
        val modulus = jo.optString("modulus")
        val exponent = jo.optString("exponent", "AQAB")
        if (modulus.isEmpty()) throw IOException("获取登录公钥失败")
        Log.d(TAG, "publicKey modulus.len=${modulus.length} exponent=$exponent")

        // 登录页里的 csrftoken（部分版本需要）
        val page = try { httpGet(LOGIN_PAGE) } catch (e: Exception) { "" }
        val csrf0 = extractCsrf(page)
        Log.d(TAG, "login page len=${page.length} csrf=${if (csrf0.isEmpty()) "none" else csrf0.take(12) + "…"}")

        var ok = false
        var lastResp = ""
        // 正方不同部署的密文格式不同：Base64 或十六进制，依次尝试
        for ((label, hexMode) in listOf("b64" to false, "hex" to true)) {
            val encPass = rsaEncrypt(pass, modulus, exponent, hexMode)
            val pageNow = if (label == "b64") page
                else try { httpGet(LOGIN_PAGE) } catch (e: Exception) { page }
            val csrf = extractCsrf(pageNow).ifEmpty { csrf0 }

            // 登录页所有 hidden 字段原样带上（mmsfjm=1 表示密码已加密、xxdm 学校代码等）
            val fields = parseHiddenFields(pageNow)
            fields["yhm"] = user
            fields["mm"] = encPass
            if (csrf.isNotEmpty()) fields["csrftoken"] = csrf
            val form = fields.entries.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }

            lastResp = httpPost(LOGIN_PAGE, form, LOGIN_PAGE, ajax = false)
            ok = loggedIn()
            Log.d(TAG, "login[$label] fields=${fields.keys.joinToString(",")} enc.len=${encPass.length} " +
                    "resp.len=${lastResp.length} ok=$ok hint=${errorHint(lastResp)}")
            if (ok) break
        }
        if (!ok) {
            lastError = when {
                lastResp.contains("验证码") -> "登录需要验证码，当前版本暂不支持"
                lastResp.contains("csrf", true) || lastResp.contains("CSRF") -> "登录校验失败（csrf token）"
                lastResp.contains("锁定") || lastResp.contains("冻结") -> "账号被锁定或冻结"
                lastResp.contains("密码") || lastResp.contains("用户名") -> "学号或密码不正确"
                else -> "登录被拒绝"
            }
            Log.d(TAG, "login failed: $lastError")
        } else {
            Log.d(TAG, "login success, cookies=${cookieNames()}")
        }
        return ok
    }

    /** 从登录页正文里提取可能的报错文字，便于排查。 */
    private fun errorHint(body: String): String {
        val keys = listOf("用户名", "密码", "验证码", "错误", "锁定", "冻结", "csrf", "CSRF", "失败")
        val parts = mutableListOf<String>()
        for (k in keys) {
            var i = body.indexOf(k)
            while (i >= 0 && parts.size < 8) {
                val s = body.substring(maxOf(0, i - 25), minOf(body.length, i + 35))
                    .replace(Regex("\\s+"), " ")
                if (!parts.contains(s)) parts.add(s)
                i = body.indexOf(k, i + 1)
            }
        }
        return parts.joinToString(" || ")
    }

    /** 登录页 csrftoken：兼容属性顺序不同的写法。 */
    private fun extractCsrf(page: String): String {
        val tag = Regex("<input[^>]*id=\"csrftoken\"[^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
            ?: Regex("<input[^>]*name=\"csrftoken\"[^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
            ?: return ""
        return Regex("value=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: ""
    }

    /** 解析登录页所有 type=hidden 的字段（值可能带引号或不带）。 */
    private fun parseHiddenFields(page: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (m in Regex("<input[^>]*type=\"hidden\"[^>]*>", RegexOption.IGNORE_CASE).findAll(page)) {
            val tag = m.value
            val name = Regex("name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
                ?: Regex("name\\s*=\\s*([^ >]+)").find(tag)?.groupValues?.get(1)?.trim('"', '\'')
                ?: continue
            val value = Regex("value\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
                ?: Regex("value\\s*=\\s*([^ >\\r\\n]*)").find(tag)?.groupValues?.get(1)?.trim('"', '\'')
                ?: ""
            if (name.isNotEmpty()) map[name] = value
        }
        return map
    }

    private fun cookieNames(): String {
        return try {
            val cm = CookieHandler.getDefault() as? CookieManager
            cm?.cookieStore?.cookies?.joinToString(",") { it.name } ?: "none"
        } catch (e: Exception) { "err" }
    }

    /** 当前会话是否已登录。 */
    fun loggedIn(): Boolean {
        return try {
            val body = httpGet(INDEX_URL)
            val ok = body.isNotBlank() && !body.contains("login_slogin") && !body.contains("用户登录")
            Log.d(TAG, "index len=${body.length} ok=$ok head=${body.take(100).replace("\n", " ")}")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "index err ${e.message}")
            false
        }
    }

    private fun rsaEncrypt(text: String, modulusB64: String, expB64: String, hex: Boolean = false): String {
        val modBytes = Base64.decode(modulusB64, Base64.NO_WRAP)
        val expBytes = Base64.decode(expB64, Base64.NO_WRAP)
        val spec = RSAPublicKeySpec(BigInteger(1, modBytes), BigInteger(1, expBytes))
        val pub = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        val out = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return if (hex) out.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        else Base64.encodeToString(out, Base64.NO_WRAP)
    }

    // ---------- 学期 / 课表 ----------

    /** 按当前日期推学年与学期（正方：xnm=起始年，xqm 1/2/3）。 */
    fun semesterParams(): Pair<String, String> {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        return when {
            m >= 9 -> y.toString() to "1"
            m == 1 -> (y - 1).toString() to "1"
            m in 2..6 -> (y - 1).toString() to "2"
            else -> (y - 1).toString() to "3"
        }
    }

    /** 登录并拉取整学期课表，原始 JSON 落到 jwxt_semester_json。 */
    fun fetchSemester(prefs: SharedPreferences): JSONObject {
        val cookie = ensureLogin(prefs)

        val (xnm, xqm) = semesterParams()
        val raw = httpPost(KW_URL, "xnm=$xnm&xqm=$xqm", HOME, cookie = cookie)
        if (raw.isBlank()) throw IOException("课表接口无返回")
        val jo = try { JSONObject(raw) } catch (e: Exception) { throw IOException("课表返回格式异常") }
        if (jo.optJSONArray("kbList") == null) throw IOException("未获取到课表（可能登录状态失效）")

        prefs.edit()
            .putString("jwxt_semester_json", raw)
            .putString("jwxt_xnm", xnm)
            .putString("jwxt_xqm", xqm)
            .apply()
        // 校区 ID（作息表接口必填）。课表返回里 xqh1 形如 "4,"，挂在 xsxx 或每条 kbList 上
        val xqh = jo.optJSONObject("xsxx")?.optString("xqh1", "")?.takeIf { it.isNotBlank() }
            ?: jo.optJSONArray("kbList")?.optJSONObject(0)?.optString("xqh1", "") ?: ""
        xqh.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString("jwxt_xqh_id", it).apply()
        }
        return jo
    }

    /**
     * 拉取本学期作息表（每节上下课时间），落盘 section_times_json。
     * 与周次表同属“学期级”数据：完全更新时必拉，普通刷新只在本地没有时补拉。
     */
    fun fetchSectionTimes(prefs: SharedPreferences, cookie: String = ""): Boolean {
        return try {
            val ck = cookie.ifEmpty { ensureLogin(prefs) }
            val (defXnm, defXqm) = semesterParams()
            val xnm = prefs.getString("jwxt_xnm", "")?.takeIf { it.isNotBlank() } ?: defXnm
            val xqm = prefs.getString("jwxt_xqm", "")?.takeIf { it.isNotBlank() } ?: defXqm
            val xqh = prefs.getString("jwxt_xqh_id", "")?.trim() ?: ""
            if (xqh.isEmpty()) {
                Log.d(TAG, "sectionTimes 跳过：还没有校区 ID（先拉一次课表）")
                return false
            }
            val raw = httpPost(SJ_URL,
                "xnm=$xnm&xqm=$xqm&xqh_id=$xqh", HOME, cookie = ck)
            val arr = parseTimeArray(raw) ?: return false
            if (arr.length() == 0) return false
            prefs.edit()
                .putString("section_times_json", raw)
                .putLong("section_times_update", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "sectionTimes ${arr.length()} 节")
            true
        } catch (e: Exception) {
            Log.d(TAG, "sectionTimes fail: ${e.message}")
            false
        }
    }

    /** 返回的可能是数组，也可能包在 items/data/rows 里。 */
    private fun parseTimeArray(raw: String?): JSONArray? {
        val txt = raw?.trim() ?: return null
        if (txt.isEmpty()) return null
        return try {
            if (txt.startsWith("[")) JSONArray(txt)
            else {
                val jo = JSONObject(txt)
                jo.optJSONArray("items") ?: jo.optJSONArray("data") ?: jo.optJSONArray("rows")
            }
        } catch (e: Exception) { null }
    }

    /** "08:40:00" → "08:40"；异常值原样返回截断后的前 5 位。 */
    private fun hm(t: String): String {
        val s = t.trim()
        if (s.length >= 5) return s.substring(0, 5)
        return ""
    }

    /**
     * 当前生效的节次作息表：优先本地接口数据，缺失时用默认表。
     * @return (节次, "起-止")
     */
    fun sectionTimes(prefs: SharedPreferences): List<Pair<Int, String>> {
        val raw = prefs.getString("section_times_json", null)
        if (!raw.isNullOrBlank()) {
            val arr = parseTimeArray(raw)
            if (arr != null && arr.length() > 0) {
                val out = ArrayList<Pair<Int, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val no = o.optString("jcmc").toIntOrNull()?.takeIf { it > 0 } ?: (i + 1)
                    val q = hm(o.optString("qssj"))
                    val j = hm(o.optString("jssj"))
                    if (q.isEmpty() || j.isEmpty()) continue
                    out.add(no to "$q-$j")
                }
                if (out.isNotEmpty()) return out.sortedBy { it.first }
            }
        }
        return DEFAULT_SECTION_TIMES.mapIndexed { i, s -> (i + 1) to s }
    }

    /**
     * 取某一周的课表（超星同构 JSON）。
     * @param forceRefresh true 时重新登录并拉取整学期，false 时用本地整学期缓存。
     */
    fun fetchWeek(prefs: SharedPreferences, week: Int, forceRefresh: Boolean = false): String {
        var raw = prefs.getString("jwxt_semester_json", null)
        if (forceRefresh || raw.isNullOrEmpty()) {
            raw = fetchSemester(prefs).toString()
        }
        val kb = JSONObject(raw!!)
        return buildWeekJson(kb, week, prefs)
    }

    /**
     * 拉取学期周次清单（接口 N2154）：每项含 zs（周次）与 rq（该周周一/周日），
     * 是学校口径的真实周次表，落盘 week_index_json。失败返回 null（不影响其他流程）。
     */
    fun fetchWeekIndex(prefs: SharedPreferences): JSONArray? {
        return try {
            val cookie = ensureLogin(prefs)
            val (xnm, xqm) = semesterParams()
            val raw = httpPost(ZC_URL, "xnm=$xnm&xqm=$xqm", ZC_PAGE, cookie = cookie)
            if (raw.isBlank()) return null
            val arr: JSONArray? = when {
                raw.trimStart().startsWith("[") -> JSONArray(raw)
                else -> {
                    val jo = JSONObject(raw)
                    jo.optJSONArray("items") ?: jo.optJSONArray("data") ?: jo.optJSONArray("rows")
                }
            }
            if (arr != null) {
                prefs.edit().putString("week_index_json", raw).apply()
                Log.d(TAG, "weekIndex ${arr.length()} 周")
            }
            arr
        } catch (e: Exception) {
            Log.d(TAG, "weekIndex fail: ${e.message}")
            null
        }
    }

    private fun todayIso(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 从周次清单找"今天"落在第几周；找不到返回 0。 */
    private fun weekFromIndex(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val arr = try { JSONArray(raw) } catch (e: Exception) { return 0 }
        val today = todayIso()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val parts = it.optString("rq").split("/")
            if (parts.size != 2) continue
            val s = parts[0].trim()
            val e = parts[1].trim()
            if (s.length == 10 && e.length == 10 && today >= s && today <= e) {
                val zs = it.optInt("zs", 0)
                if (zs > 0) return zs
            }
        }
        return 0
    }

    /** 周次清单的最大周次；无数据返回 0。 */
    private fun maxZcOf(arr: JSONArray?): Int {
        if (arr == null) return 0
        var max = 0
        for (i in 0 until arr.length()) {
            val zs = arr.optJSONObject(i)?.optInt("zs", 0) ?: 0
            if (zs > max) max = zs
        }
        return max
    }

    /** 周次清单里第一周的开始日期（yyyy-MM-dd）；无数据返回 null。 */
    private fun firstWeekStart(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val rq = arr.optJSONObject(0)?.optString("rq") ?: return null
        return rq.split("/").firstOrNull()?.trim()?.takeIf { it.length == 10 }
    }

    /** 每周周一的日期映射（week -> yyyy-MM-dd），来自本地周次表；用于表头日期与课程日期缺失时的兜底。 */
    fun weekStartMap(prefs: SharedPreferences): Map<Int, String> {
        val raw = prefs.getString("week_index_json", null) ?: return emptyMap()
        val out = HashMap<Int, String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val zs = o.optInt("zs", 0)
                val start = o.optString("rq").split("/").firstOrNull()?.trim() ?: ""
                if (zs > 0 && start.length == 10) out[zs] = start
            }
        } catch (e: Exception) { }
        return out
    }

    /**
     * 全量刷新：登录一次、拉整学期，然后生成 1..maxWeek 的每周缓存。
     * @return (当前真实周, 总周数)；失败抛异常。
     */
    fun refreshAll(prefs: SharedPreferences): Pair<Int, Int> {
        val kb = fetchSemester(prefs)
        // 学期级数据一起拿全：节次作息 + 周次表
        fetchSectionTimes(prefs)
        val kbList = kb.optJSONArray("kbList") ?: JSONArray()
        val idxArr = fetchWeekIndex(prefs)
        val maxWeek = maxOf(maxWeekOf(kbList), maxZcOf(idxArr)).coerceAtLeast(1)
        // 首次拿到周次表时，把真实开学日期写进设置（用户手动填过则不覆盖）
        if (prefs.getString("semester_start", "").isNullOrBlank()) {
            firstWeekStart(idxArr)?.let { prefs.edit().putString("semester_start", it).apply() }
        }
        val current = currentWeekOf(kb.optJSONObject("xsxx") ?: JSONObject(),
            prefs.getString("jwxt_xqm", "1") ?: "1", maxWeek, prefs)
        for (w in 1..maxWeek) {
            val json = buildWeekJson(kb, w, prefs)
            prefs.edit().putString("weekCache_$w", json)
                .putLong("weekUpdate_$w", System.currentTimeMillis()).apply()
        }
        // 清掉超出本学期周数的旧缓存
        for (k in prefs.all.keys) {
            if (k.startsWith("weekCache_")) {
                val w = k.removePrefix("weekCache_").toIntOrNull() ?: continue
                if (w > maxWeek) prefs.edit().remove(k).remove("weekUpdate_$w").apply()
            }
        }
        prefs.edit().putInt("real_week", current).apply()
        return current to maxWeek
    }

    /**
     * 只更新课表（普通刷新）：拉整学期课表并重建各周缓存，不主动拉周次表；
     * 仅当本地还没有周次表时补拉一次，保证周次仍能算准。
     * @return (当前真实周, 总周数)；失败抛异常。
     */
    fun refreshKbOnly(prefs: SharedPreferences): Pair<Int, Int> {
        val kb = fetchSemester(prefs)
        if (prefs.getString("week_index_json", null).isNullOrBlank()) {
            fetchWeekIndex(prefs)
        }
        if (prefs.getString("section_times_json", null).isNullOrBlank()) {
            fetchSectionTimes(prefs)
        }
        val kbList = kb.optJSONArray("kbList") ?: JSONArray()
        val idxArr = try {
            prefs.getString("week_index_json", null)?.let { JSONArray(it) }
        } catch (e: Exception) { null }
        val maxWeek = maxOf(maxWeekOf(kbList), maxZcOf(idxArr)).coerceAtLeast(1)
        val current = currentWeekOf(kb.optJSONObject("xsxx") ?: JSONObject(),
            prefs.getString("jwxt_xqm", "1") ?: "1", maxWeek, prefs)
        val now = System.currentTimeMillis()
        for (w in 1..maxWeek) {
            prefs.edit().putString("weekCache_$w", buildWeekJson(kb, w, prefs))
                .putLong("weekUpdate_$w", now).apply()
        }
        for (k in prefs.all.keys) {
            if (k.startsWith("weekCache_")) {
                val w = k.removePrefix("weekCache_").toIntOrNull() ?: continue
                if (w > maxWeek) prefs.edit().remove(k).remove("weekUpdate_$w").apply()
            }
        }
        prefs.edit().putInt("real_week", current).apply()
        return current to maxWeek
    }

    /**
     * 只重算周次，不请求网络：用本地已保存的整学期 JSON 重建每周缓存。
     * 用于启动时校准真实本周。无本地数据时返回 null。
     */
    fun recalcWeeks(prefs: SharedPreferences): Pair<Int, Int>? {
        val raw = prefs.getString("jwxt_semester_json", null) ?: return null
        val kb = try { JSONObject(raw) } catch (e: Exception) { return null }
        val kbList = kb.optJSONArray("kbList") ?: return null
        val maxWeek = maxWeekOf(kbList).coerceAtLeast(1)
        val current = currentWeekOf(kb.optJSONObject("xsxx") ?: JSONObject(),
            prefs.getString("jwxt_xqm", "1") ?: "1", maxWeek, prefs)
        for (w in 1..maxWeek) {
            prefs.edit().putString("weekCache_$w", buildWeekJson(kb, w, prefs)).apply()
        }
        prefs.edit().putInt("real_week", current).apply()
        return current to maxWeek
    }

    // ---------- 转换 ----------

    private fun buildWeekJson(kb: JSONObject, week: Int, prefs: SharedPreferences): String {
        val kbList = kb.optJSONArray("kbList") ?: JSONArray()
        val xsxx = kb.optJSONObject("xsxx") ?: JSONObject()
        val xnm = prefs.getString("jwxt_xnm", "") ?: ""
        val xqm = prefs.getString("jwxt_xqm", "") ?: ""
        val maxWeek = maxWeekOf(kbList).coerceAtLeast(1)
        val current = currentWeekOf(xsxx, xqm, maxWeek, prefs)

        var maxSection = 0
        for (i in 0 until kbList.length()) {
            val it = kbList.getJSONObject(i)
            val (begin, len) = parseSection(sectionStr(it))
            if (begin > 0) maxSection = maxOf(maxSection, begin + len - 1)
        }
        val timeCount = maxOf(sectionTimes(prefs).size, maxSection).coerceAtLeast(1)
        val times = sectionTimes(prefs)

        val lessonArray = JSONArray()
        for (i in 0 until kbList.length()) {
            val it = kbList.getJSONObject(i)
            val name = it.optString("kcmc")
            if (name.isEmpty()) continue
            val day = it.optString("xqj").toIntOrNull()
                ?: it.optInt("xqj", 0).takeIf { it in 1..7 } ?: 0
            if (day !in 1..7) continue
            val (begin, len) = parseSection(sectionStr(it))
            if (begin <= 0) continue
            if (!parseZcd(it.optString("zcd")).contains(week)) continue

            lessonArray.put(JSONObject()
                .put("name", name)
                .put("dayOfWeek", day)
                .put("beginNumber", begin)
                .put("length", len)
                .put("location", it.optString("cdmc"))
                .put("teacherName", it.optString("xm"))
                .put("weeks", it.optString("zcd"))
                .put("day", ""))
        }

        val timeConfig = JSONArray()
        for (i in 0 until timeCount) {
            timeConfig.put(times.getOrNull(i)?.second ?: "")
        }
        val campusArr = JSONArray()
        campusArr.put(JSONObject().put("name", "默认").put("timeConfig", timeConfig))

        val curriculum = JSONObject()
            .put("uuid", "jwxt")
            .put("schoolYear", xnm)
            .put("semester", xqm)
            .put("currentWeek", current)
            .put("maxWeek", maxWeek)
            .put("campusLessonTimeConfigArray", campusArr)

        return JSONObject().put("result", 1)
            .put("data", JSONObject().put("curriculum", curriculum)
                .put("lessonArray", lessonArray))
            .toString()
    }

    /** 节次字段兼容不同版本：jc / jcs / jcstr。 */
    private fun sectionStr(it: JSONObject): String {
        for (k in listOf("jc", "jcs", "jcstr", "jcor")) {
            val v = it.optString(k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** "1-2节" / "1,2节" / "1-2,3-4节" → (起始节, 节数)。 */
    private fun parseSection(s: String): Pair<Int, Int> {
        val m = Regex("(\\d+)(?:-(\\d+))?").find(s) ?: return 0 to 0
        val a = m.groupValues[1].toIntOrNull() ?: return 0 to 0
        val b = m.groupValues[2].toIntOrNull() ?: a
        if (a <= 0) return 0 to 0
        return a to (b - a + 1).coerceAtLeast(1)
    }

    /** "1-16周" / "1-8,10-16周" / "1,3,5周" / "1-16周(单)" → 周次集合。 */
    private fun parseZcd(zcd: String): Set<Int> {
        val set = mutableSetOf<Int>()
        if (zcd.isBlank()) return set
        val txt = zcd.replace("（", "(").replace("）", ")")
        val onlyOdd = txt.contains("单")
        val onlyEven = txt.contains("双")
        for (p in txt.split(",", "，")) {
            val m = Regex("(\\d+)(?:-(\\d+))?").find(p) ?: continue
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].toIntOrNull() ?: a
            for (w in a..b) {
                if (onlyOdd && w % 2 == 0) continue
                if (onlyEven && w % 2 == 1) continue
                set.add(w)
            }
        }
        return set
    }

    private fun maxWeekOf(kbList: JSONArray): Int {
        var max = 1
        for (i in 0 until kbList.length()) {
            parseZcd(kbList.getJSONObject(i).optString("zcd")).maxOrNull()?.let { if (it > max) max = it }
        }
        return max
    }

    /** 当前周：优先用学校周次表（今天落在哪一周），其次手动开学日期，再次 xsxx，最后默认开学周。 */
    private fun currentWeekOf(xsxx: JSONObject, xqm: String, maxWeek: Int,
                              prefs: SharedPreferences? = null): Int {
        // 1) 学校口径：周次清单里今天所处的周
        val fromIdx = weekFromIndex(prefs?.getString("week_index_json", null))
        if (fromIdx > 0) return fromIdx.coerceIn(1, maxWeek)
        // 2) 手动设置的开学日期
        val manual = prefs?.getString("semester_start", "") ?: ""
        val startStr = listOf("XQKSRQ", "xqksrq", "XQKSRQ1", "ksrq")
            .firstOrNull { xsxx.optString(it).isNotBlank() }?.let { xsxx.optString(it) } ?: ""
        val start = parseDate(manual) ?: parseDate(startStr) ?: defaultStart(xqm)
        val today = Calendar.getInstance()
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val days = ((today.timeInMillis - startCal.timeInMillis) / (24 * 3600 * 1000L)).toInt()
        val w = (days / 7) + 1
        return w.coerceIn(1, maxWeek)
    }

    /** 默认开学：秋季取 9 月第一个周一，春季取 2 月 23 日所在周周一，短学期取 7 月 1 日所在周周一。 */
    private fun defaultStart(xqm: String): Long {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val startYear = if (xqm == "2" || xqm == "3" || (m in 2..8)) y else y
        val cal = Calendar.getInstance().apply {
            clear()
            when (xqm) {
                "2" -> set(startYear, 1, 23)
                "3" -> set(startYear, 6, 1)
                else -> set(startYear, 8, 1)
            }
        }
        if (xqm == "2" || xqm == "3") {
            // 对齐到所在周周一
            cal.add(Calendar.DAY_OF_YEAR, -(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
        } else {
            // 秋季：从 9 月 1 日往后找本周之后的第一个周一（9 月 1 日恰为周一则用它）
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun parseDate(s: String): Long? {
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(s) ?: return null
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        return cal.timeInMillis
    }

    // ---------- HTTP ----------

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String, cookie: String = ""): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html,application/json,*/*")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            Log.d(TAG, "GET ${url.substringAfter("/jwglxt")} -> $code redirect=${conn.getHeaderField("Location")}")
            if (code >= 400) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun httpPost(url: String, form: String, referer: String,
                         ajax: Boolean = true, cookie: String = ""): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            if (ajax) conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept",
                if (ajax) "application/json, text/javascript, */*; q=0.01"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "POST ${url.substringAfter("/jwglxt")} -> $code redirect=${conn.getHeaderField("Location")} cookies=${cookieNames()}")
            if (code >= 400) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }
}
