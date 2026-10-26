package com.videoparser.bili

import java.net.URLDecoder

/**
 * B 站 Cookie：清洗、登录态判断与清晰度偏好。
 *
 * 只做纯文本处理，不依赖 Android，方便单元测试；读写由 [BiliCookieStore] 负责。
 *
 * 支持的粘贴格式：
 * - 浏览器开发者工具里整段 Cookie 头：`SESSDATA=xxx; bili_jct=yyy; buvid3=zzz`
 * - 文档里原样复制的 JSON：`{"SESSDATA": "xxx", "bili_jct": "yyy"}`
 * - 只粘了 SESSDATA 或 bili_jct 的值（按特征自动补键名）
 */
object BiliCookie {

    const val KEY_SESSDATA = "SESSDATA"
    const val KEY_JUMP = "bili_jct"
    const val KEY_BUVUID = "buvid3"
    const val KEY_DEDE_USER_ID = "DedeUserID"

    /** 输出 Cookie 头时优先排在前面的键 */
    private val ORDERED_KEYS = listOf(
        KEY_SESSDATA,
        KEY_JUMP,
        KEY_BUVUID,
        KEY_DEDE_USER_ID,
        "buvid4",
        "buvid_fp",
        "bili_ticket",
        "bili_ticket_expires",
        "sid"
    )

    /** 当前生效的 Cookie 头（空串表示游客模式），由 BiliViewModel 在启动 / 保存时写入 */
    @Volatile
    var current: String = ""
        private set

    /** 当前生效的 Cookie 键值对 */
    @Volatile
    private var currentPairs: Map<String, String> = emptyMap()

    /** 上次实际解析到的清晰度（0 表示还不知道） */
    @Volatile
    private var rememberedQuality: Int = 0

    /** 更新当前生效的 Cookie；返回是否已登录（带 SESSDATA） */
    fun apply(raw: String?): Boolean {
        val pairs = parsePairs(raw)
        currentPairs = pairs
        current = buildHeader(pairs)
        // 缺少 buvid3 时补一个稳定的设备指纹并落到当前 Cookie 上，
        // 保证 SESSDATA 在服务端校验时与设备指纹配套（游客 Cookie 不受影响）
        if (hasSession(pairs) && pairs[KEY_BUVUID].isNullOrBlank()) {
            val completed = LinkedHashMap(pairs)
            completed[KEY_BUVUID] = generateBuvid3()
            currentPairs = completed
            current = buildHeader(completed)
        }
        return hasSession(pairs)
    }

    /** 清洗用户粘贴的内容，得到规范的 Cookie 串 */
    fun sanitize(raw: String?): String = buildHeader(parsePairs(raw))

    fun isLoggedIn(raw: String? = current): Boolean = hasSession(pairsOf(raw))

    fun userId(raw: String? = current): String =
        pairsOf(raw)[KEY_DEDE_USER_ID].orEmpty()

    /* ───────────────────────── 解析与清洗 ───────────────────────── */

    /** 解析成有序键值对；兼容 JSON、裸值、多行与夹带说明文字的粘贴内容 */
    fun parsePairs(raw: String?): Map<String, String> {
        val text = normalizeText(raw)
        if (text.isEmpty()) return emptyMap()

        // 1. 整段 JSON
        if (text.startsWith("{")) {
            val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
            if (obj != null) {
                val out = LinkedHashMap<String, String>()
                obj.keys().forEach { key ->
                    val value = obj.optString(key).trim()
                    if (key.isNotBlank() && isCookieValue(value)) out.putIfAbsent(key.trim(), value)
                }
                if (out.isNotEmpty()) return out
            }
        }

        // 2. 标准 Cookie 头
        val pairs = LinkedHashMap<String, String>()
        text.split(';').forEach { part ->
            val item = part.trim()
            val eq = item.indexOf('=')
            if (eq <= 0) return@forEach
            val key = item.substring(0, eq).trim()
            val value = item.substring(eq + 1).trim().trim('"', '\'')
            if (isKeyName(key) && isCookieValue(value)) pairs.putIfAbsent(key, value)
        }
        if (pairs.isNotEmpty()) return pairs

        // 3. 只粘了值：按特征猜键名
        val value = text.trim().trim('"', '\'', ' ')
        if (value.isEmpty() || value.any { it == ' ' }) return emptyMap()
        return when {
            // bili_jct：32 位十六进制
            value.matches(Regex("^[0-9a-fA-F]{32}$")) -> mapOf(KEY_JUMP to value)
            // SESSDATA：Base64url，逗号被编码成 %2C
            value.matches(Regex("^[0-9A-Za-z_.*%-]{16,}$")) -> mapOf(KEY_SESSDATA to value)
            else -> emptyMap()
        }
    }

    /** 输出 Cookie 头：常用键排在前面，其余保持原有顺序 */
    fun buildHeader(pairs: Map<String, String>): String {
        if (pairs.isEmpty()) return ""
        val builder = StringBuilder()
        val written = HashSet<String>()
        ORDERED_KEYS.forEach { key ->
            val value = pairs[key]
            if (value.isNullOrBlank() || !written.add(key.lowercase())) return@forEach
            if (builder.isNotEmpty()) builder.append("; ")
            builder.append(key).append('=').append(value)
        }
        pairs.forEach { (key, value) ->
            if (value.isBlank() || !written.add(key.lowercase())) return@forEach
            if (builder.isNotEmpty()) builder.append("; ")
            builder.append(key).append('=').append(value)
        }
        return builder.toString()
    }

    /** 脱敏展示：`SESSDATA=abc***xyz; bili_jct=def***456` */
    fun mask(raw: String? = current): String {
        val pairs = pairsOf(raw)
        if (pairs.isEmpty()) return ""
        return pairs.entries.joinToString("; ") { (key, value) -> "$key=${maskValue(value)}" }
    }

    /** 把文本里的 SESSDATA 值替换成 ***，可安全写进提示或日志 */
    fun redact(text: String): String =
        text.replace(Regex("(SESSDATA=)[^;&\\s]+", RegexOption.IGNORE_CASE), "$1***")

    /* ───────────────────────── 清晰度偏好 ───────────────────────── */

    /** 上次实际拿到的清晰度（0 表示还不知道）；登录后下次直接从该档位请求 */
    val preferredQuality: Int get() = rememberedQuality

    fun restorePreferences(preferred: Int) {
        rememberedQuality = preferred.coerceIn(0, 127)
    }

    /** 记住本次实际拿到的清晰度（游客固定 720P，不需要记录） */
    fun rememberQuality(qn: Int, loggedIn: Boolean = isLoggedIn()) {
        if (!loggedIn || qn <= 0) return
        rememberedQuality = qn.coerceIn(0, 127)
    }

    /** 生成形如真实 buvid3 的设备指纹：36 位 UUID + infoc 后缀 */
    fun generateBuvid3(): String {
        val random = java.util.Random()
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(44)
        for (i in 0 until 36) {
            if (i == 8 || i == 13 || i == 18 || i == 23) sb.append('-')
            sb.append(hex[random.nextInt(16)])
        }
        sb.append(10_000 + random.nextInt(80_000))
        sb.append("infoc")
        return sb.toString()
    }

    /* ───────────────────────── 内部工具 ───────────────────────── */

    private fun pairsOf(raw: String?): Map<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        if (text == current && currentPairs.isNotEmpty()) return currentPairs
        return parsePairs(text)
    }

    private fun hasSession(pairs: Map<String, String>): Boolean =
        !pairs[KEY_SESSDATA].isNullOrBlank()

    private fun normalizeText(raw: String?): String {
        var text = raw?.replace('\u00A0', ' ')?.replace('\u3000', ' ')?.trim().orEmpty()
        if (text.isEmpty()) return ""
        text = text.removePrefix("Cookie:").removePrefix("cookie:").trim()
        // 直接复制了带引号的文档内容时，去掉外层引号
        text = text.removePrefix("\"").removeSuffix("\"").trim()
        // 多行 Cookie 合并成一行
        text = text.replace(Regex("\\s*[\\r\\n]+\\s*"), "; ")
        // URL 编码过的整段 Cookie
        if (!text.contains('=') && text.contains("%3D")) {
            runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()?.let { decoded ->
                if (decoded.contains('=')) text = decoded
            }
        }
        return text
    }

    /** 键名只能是 ASCII 可见字符，避免把说明文字当成 Cookie 键 */
    private fun isKeyName(key: String): Boolean =
        key.length <= 40 && key.none { it.code !in 0x21..0x7E }

    /** 值里出现中文、空白或分隔符就当作说明文字丢弃 */
    private fun isCookieValue(value: String): Boolean {
        if (value.isEmpty() || value.length > 2048) return false
        return value.none { it.code > 0x7E || it == ' ' || it == '\t' || it == ';' }
    }

    private fun maskValue(value: String): String {
        val v = value.trim()
        return when {
            v.length <= 6 -> "*".repeat(v.length)
            v.length <= 12 -> v.take(2) + "***" + v.takeLast(2)
            else -> v.take(4) + "***" + v.takeLast(4)
        }
    }
}
