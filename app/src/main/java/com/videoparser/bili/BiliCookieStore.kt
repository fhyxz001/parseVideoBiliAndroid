package com.videoparser.bili

import android.content.Context
import android.content.SharedPreferences

/**
 * Cookie 与清晰度偏好的本地存储（SharedPreferences）。
 *
 * - Cookie 只保存在应用私有目录，不上传到任何服务器；未 root 的手机上其它应用读不到该文件。
 * - 服务端校验 SESSDATA 时要求带上配套的设备指纹 buvid3，用户手动粘贴的 Cookie 往往没有它，
 *   因此保存时会自动生成并一并持久化（见 [BiliCookie.apply]）。
 */
object BiliCookieStore {

    private const val PREFS_NAME = "bili_cookie"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_QUALITY = "quality"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取并应用已保存的 Cookie；返回是否处于登录状态 */
    fun load(context: Context): Boolean {
        val prefs = prefs(context)
        val loggedIn = BiliCookie.apply(prefs.getString(KEY_COOKIE, null).orEmpty())
        BiliCookie.restorePreferences(prefs.getInt(KEY_QUALITY, 0))
        return loggedIn
    }

    /** 当前保存的 Cookie（空串表示游客模式） */
    fun saved(context: Context): String =
        prefs(context).getString(KEY_COOKIE, null).orEmpty()

    /**
     * 校验并保存用户粘贴的 Cookie，返回清洗后（含补齐的设备指纹）的结果。
     * @throws IllegalArgumentException 内容无法识别或缺 SESSDATA 时抛出，由调用方提示
     */
    fun save(context: Context, rawInput: String): String {
        val cleaned = BiliCookie.sanitize(rawInput)
        val pairs = BiliCookie.parsePairs(cleaned)
        require(pairs.isNotEmpty()) { "没有识别到 Cookie，请检查粘贴的内容" }
        require(!pairs[BiliCookie.KEY_SESSDATA].isNullOrBlank()) {
            "Cookie 里缺少 SESSDATA，无法登录（请复制完整的 Cookie）"
        }
        persist(context, cleaned)
        return saved(context)
    }

    private fun persist(context: Context, cleaned: String) {
        // BiliCookie.apply 会补全 buvid3，补全后的结果才是要持久化的 Cookie
        BiliCookie.apply(cleaned)
        prefs(context).edit()
            .putString(KEY_COOKIE, BiliCookie.current)
            .putInt(KEY_QUALITY, 0)
            .apply()
        BiliCookie.restorePreferences(0)
    }

    /** 保存本次实际拿到的清晰度，下次解析直接从这个档位起步 */
    fun saveQuality(context: Context, qn: Int) {
        if (qn <= 0) return
        val clamped = qn.coerceIn(0, 127)
        prefs(context).edit().putInt(KEY_QUALITY, clamped).apply()
        BiliCookie.restorePreferences(clamped)
    }

    /** 退出登录：清除 Cookie 与清晰度偏好 */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_COOKIE)
            .remove(KEY_QUALITY)
            .apply()
        BiliCookie.apply("")
        BiliCookie.restorePreferences(0)
    }
}
