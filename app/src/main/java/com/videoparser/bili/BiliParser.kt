package com.videoparser.bili

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 视频作者信息 */
data class BiliAuthor(
    val mid: String = "",
    val name: String = "",
    val avatar: String = ""
)

/** 解析结果：一条 B 站视频所需要的全部信息 */
data class BiliVideo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val coverUrl: String,
    val videoUrl: String,
    val durationSec: Int,
    val pageCount: Int,
    val qualityLabel: String,
    val author: BiliAuthor,
    /** 清晰度 id（B 站 qn），0 表示未知 */
    val quality: Int = 0,
    /** 本次解析是否使用了登录 Cookie */
    val loggedIn: Boolean = false
)

sealed class BiliParseResult {
    data class Success(val data: BiliVideo) : BiliParseResult()
    data class Error(val message: String) : BiliParseResult()
}

internal class BiliException(message: String) : Exception(message)

internal data class VideoId(val bvid: String?, val aid: Long?)

internal data class ViewInfo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val pageCount: Int,
    val cid: Long,
    val author: BiliAuthor
)

/** playurl 返回结果：MP4 直链 + 实际清晰度 */
internal data class PlayInfo(
    val url: String,
    val quality: Int,
    /** 请求时可用的清晰度 id 列表；空表示接口没返回 */
    val accepts: List<Int> = emptyList()
)

/**
 * B 站解析器。
 *
 * 流程与 python 版 parse-video-py 的 BiliBili 解析器一致：
 *   分享链接 / BV 号 / av 号
 *     -> (b23.tv 短链跟随跳转)
 *     -> api.bilibili.com/x/web-interface/view   拿标题、封面、UP 主、cid
 *     -> api.bilibili.com/x/player/playurl     拿 mp4 直链
 *
 * 清晰度策略：
 * - 游客（platform=html5，不带 Cookie）：最多 720P；
 * - 配置 Cookie 后改用 platform=pc、fnval=1 请求，可拿到 1080P / 1080P+ / 1080P60，
 *   大会员账号还能拿到 4K / HDR / 杜比。请求时优先申请记住的最高档位，拿不到再逐级回落。
 */
object BiliParser {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com/"

    /** 游客模式允许的最高清晰度：720P */
    const val QUALITY_GUEST_MAX = 64

    /** 1080P */
    const val QUALITY_1080P = 80

    /** 登录后按这个顺序尝试，先要最高清画质；服务端回落即停止 */
    internal val LOGIN_QUALITY_LADDER = listOf(112, 80, 116, 74, 64, 32, 16)

    /** 风控 / 登录失效相关的接口错误码，遇到时提示用户重新配置 Cookie */
    private val AUTH_ERROR_CODES = setOf(-352, -412, -401, -403)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val URL_REGEX =
        Regex("""https?://[^\s，。！？、；："'“”‘’《》【】（）()<>]+""")
    private val BV_REGEX = Regex("""BV[0-9A-Za-z]{10}""")
    private val AV_REGEX = Regex("""(?<![0-9A-Za-z])av(\d+)""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK_HOSTS = setOf("b23.tv", "bili2233.cn")

    /* ───────────────────────── 解析入口 ───────────────────────── */

    suspend fun parse(
        rawInput: String,
        cookie: String = BiliCookie.current
    ): BiliParseResult = withContext(Dispatchers.IO) {
        try {
            val input = rawInput.trim()
            if (input.isEmpty()) {
                return@withContext BiliParseResult.Error("请先粘贴 B 站链接或 BV 号")
            }

            val loggedIn = BiliCookie.isLoggedIn(cookie)
            var link = extractUrl(input)
            var id = link?.let(::parseVideoId) ?: parseVideoId(input)

            if (id == null && link != null && isShortLink(link)) {
                link = resolveRedirect(link)
                id = parseVideoId(link)
            }
            if (id == null) {
                return@withContext BiliParseResult.Error("没有识别到 B 站视频，请检查分享链接或 BV 号")
            }

            val view = fetchView(id, cookie)
            val play = fetchPlayUrl(view, cookie, loggedIn)

            BiliParseResult.Success(
                BiliVideo(
                    bvid = view.bvid,
                    aid = view.aid,
                    title = view.title.ifBlank { "B站视频" },
                    coverUrl = normalizeImageUrl(view.cover),
                    videoUrl = play.url,
                    durationSec = view.durationSec,
                    pageCount = view.pageCount,
                    qualityLabel = qualityLabel(play.quality),
                    author = view.author,
                    quality = play.quality,
                    loggedIn = loggedIn
                )
            )
        } catch (e: BiliException) {
            BiliParseResult.Error(e.message ?: "解析失败")
        } catch (e: Exception) {
            BiliParseResult.Error("网络错误，请检查网络后重试")
        }
    }

    /* ───────────────────────── 链接与 ID 识别 ───────────────────────── */

    /** 从「【标题】 https://b23.tv/xxxx」这类分享文案里提取 URL，并去掉结尾标点 */
    internal fun extractUrl(text: String): String? {
        val match = URL_REGEX.find(text) ?: return null
        return match.value.trimEnd(
            '.', ',', ';', ':', '!', '?', ')', ']', '}',
            '，', '。', '！', '？', '；', '：', '、', '）', '】', '》'
        )
    }

    /** 支持 BV 号、av 号；两者都没有时返回 null */
    internal fun parseVideoId(text: String): VideoId? {
        BV_REGEX.find(text)?.let { return VideoId(bvid = it.value, aid = null) }
        AV_REGEX.find(text)?.let { match ->
            val aid = match.groupValues[1].toLongOrNull()
            if (aid != null && aid > 0) return VideoId(bvid = null, aid = aid)
        }
        return null
    }

    private fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        return SHORT_LINK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** b23.tv 会 302 到 www.bilibili.com/video/BVxxxx；跟随跳转后取最终地址 */
    private fun resolveRedirect(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BiliException("短链接解析失败（HTTP ${response.code}）")
            }
            return response.request.url.toString()
        }
    }

    /* ───────────────────────── B 站接口 ───────────────────────── */

    private fun fetchView(id: VideoId, cookie: String): ViewInfo {
        val query = id.bvid?.let { "bvid=$it" } ?: "aid=${id.aid}"
        val json = getJson("https://api.bilibili.com/x/web-interface/view?$query", cookie)
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(apiErrorMessage(code, json.optString("message")))
        }
        val data = json.optJSONObject("data") ?: throw BiliException("B 站返回数据为空")
        return mapView(data)
    }

    /**
     * 请求 MP4 直链。
     *
     * 游客：`platform=html5`，最多 720P。
     * 登录：`platform=pc` + `fnval=1`，按 [playQualityCandidates] 从高到低尝试。
     *   playurl 不会因为 qn 过高而报错，而是直接回落：比如要 1080P+ 只给 1080P、
     *   要 4K 只给 720P。因此「返回画质 >= 请求档位」就说明已经拿到该档位，
     *   继续往更高档位要；直到某一档回落，或所有档位都试完。
     */
    private fun fetchPlayUrl(view: ViewInfo, cookie: String, loggedIn: Boolean): PlayInfo {
        if (!loggedIn) {
            return requestPlayUrl(view, cookie, qn = QUALITY_GUEST_MAX, hd = false)
        }

        val attempts = playQualityCandidates()
        var best: PlayInfo? = null
        var lastError: BiliException? = null
        for (qn in attempts) {
            val play = try {
                requestPlayUrl(view, cookie, qn, hd = true)
            } catch (e: CookieLoginException) {
                throw e
            } catch (e: BiliException) {
                lastError = e
                // 其它错误（如该视频无 durl）直接跳过该档位，用已经拿到的结果兜底
                continue
            }
            if (best == null || play.quality > best.quality) best = play
            if (play.quality < qn) break // 服务端回落到更低档位，说明这是上限
        }
        return best ?: throw (lastError ?: BiliException("没有获取到播放信息"))
    }

    private fun requestPlayUrl(view: ViewInfo, cookie: String, qn: Int, hd: Boolean): PlayInfo {
        val apiUrl = buildString {
            append("https://api.bilibili.com/x/player/playurl?otype=json&fnver=0")
            if (hd) {
                append("&fnval=1&platform=pc&high_quality=1")
            } else {
                append("&platform=html5")
            }
            append("&qn=").append(qn)
            append("&bvid=").append(view.bvid)
            append("&cid=").append(view.cid)
            if (hd) append("&fourk=1")
        }
        val json = getJson(apiUrl, cookie)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val message = apiErrorMessage(code, json.optString("message"))
            if (isAuthErrorCode(code)) throw CookieLoginException(message)
            throw BiliException(message)
        }
        val data = json.optJSONObject("data") ?: throw BiliException("没有获取到播放信息")
        return mapPlayUrl(data, qn)
    }

    /**
     * 登录后的请求顺序：先试比上次更高的档位（如上次 1080P 就试 1080P+），
     * 再回到上次档位；服务端回落即停止。一般 1~2 次请求就能确定可达画质。
     */
    private fun playQualityCandidates(): List<Int> {
        val remembered = BiliCookie.preferredQuality
        val ordered = LinkedHashSet<Int>()
        if (remembered < QUALITY_1080P) {
            // 还没拿到过高清：从 1080P+ 开始试
            ordered.add(112)
        } else {
            // 已拿到 112，说明账号上限可能就是 112；否则继续冲 4K / 1080P60
            LOGIN_QUALITY_LADDER.filter { it > remembered }.forEach { ordered.add(it) }
            ordered.add(remembered)
        }
        ordered.addAll(LOGIN_QUALITY_LADDER)
        return ordered.toList()
    }

    private fun isAuthErrorCode(code: Int): Boolean = code in AUTH_ERROR_CODES

    private fun getJson(url: String, cookie: String): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw BiliException("请求 B 站失败（HTTP ${response.code}）")
            }
            val body = response.body?.string()
                ?: throw BiliException("B 站返回内容为空")
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw BiliException("B 站返回数据格式异常")
            }
        }
    }

    /* ───────────────────────── JSON 映射（可单测） ───────────────────────── */

    internal fun mapView(data: JSONObject): ViewInfo {
        val bvid = data.optString("bvid")
        if (bvid.isBlank()) throw BiliException("没有获取到 BV 号")

        val pages = data.optJSONArray("pages")
        val pageCount = pages?.length()?.takeIf { it > 0 } ?: 1
        val cid = pages?.optJSONObject(0)?.optLong("cid", 0L)?.takeIf { it > 0 }
            ?: data.optLong("cid", 0L)
        if (cid <= 0) throw BiliException("没有获取到视频分P信息")

        val owner = data.optJSONObject("owner")
        val author = BiliAuthor(
            mid = owner?.optString("mid").orEmpty(),
            name = owner?.optString("name").orEmpty().ifBlank { "未知UP主" },
            avatar = normalizeImageUrl(owner?.optString("face").orEmpty())
        )

        return ViewInfo(
            bvid = bvid,
            aid = data.optLong("aid", 0L),
            title = data.optString("title"),
            cover = data.optString("pic"),
            durationSec = data.optInt("duration", 0),
            pageCount = pageCount,
            cid = cid,
            author = author
        )
    }

    internal fun mapPlayUrl(data: JSONObject, requestedQuality: Int = 0): PlayInfo {
        val quality = data.optInt("quality", 0).takeIf { it > 0 } ?: requestedQuality
        val accepts = data.optJSONArray("accept_quality")?.let { array ->
            (0 until array.length()).mapNotNull { array.optInt(it, 0).takeIf { q -> q > 0 } }
        }.orEmpty()
        // 登录 + fnval=1 时优先 durl（单文件 MP4，可直接下载 / 剪辑）；
        // 极少数视频只返回 DASH 时退化为取视频流地址。
        val durl = data.optJSONArray("durl")
        val direct = durl?.optJSONObject(0)?.optString("url").orEmpty()
        if (direct.isNotBlank()) {
            return PlayInfo(normalizeVideoUrl(direct), quality, accepts)
        }
        val dashVideo = data.optJSONObject("dash")?.optJSONArray("video")?.optJSONObject(0)
        val dashUrl = dashVideo?.optString("baseUrl").orEmpty()
            .ifBlank { dashVideo?.optString("base_url").orEmpty() }
        if (dashUrl.isNotBlank()) {
            val dashQuality = dashVideo?.optInt("id", 0)?.takeIf { it > 0 } ?: quality
            return PlayInfo(normalizeVideoUrl(dashUrl), dashQuality, accepts)
        }
        throw BiliException("该视频没有可用的 MP4 地址（番剧、付费或互动视频暂不支持）")
    }

    /* ───────────────────────── 展示与文案 ───────────────────────── */

    internal fun apiErrorMessage(code: Int, message: String?): String = when (code) {
        -400 -> "请求参数错误"
        -401 -> "登录状态无效，请在设置里重新配置 Cookie"
        -403 -> "访问被拒绝（可能需要登录或大会员）"
        -404 -> "视频不存在或已被删除"
        -412 -> "请求被 B 站风控拦截，请稍后再试"
        -352 -> "触发 B 站风控校验，请先配置 Cookie 或稍后再试"
        62002 -> "稿件不可见（可能已删除或设为私密）"
        62004 -> "稿件正在审核中"
        87007, 87008 -> "该视频为充电专属，暂不支持解析"
        else -> message?.takeIf { it.isNotBlank() } ?: "B 站接口返回错误（code=$code）"
    }

    internal fun qualityLabel(quality: Int): String = when (quality) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> if (quality > 0) "Q$quality" else "未知画质"
    }

    /** 该清晰度是否需要登录 / 大会员 */
    internal fun needsLoginFor(quality: Int): Boolean = quality > QUALITY_GUEST_MAX

    internal fun normalizeImageUrl(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            else -> value
        }
    }

    internal fun normalizeVideoUrl(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith("http://")) "https://" + value.removePrefix("http://") else value
    }

    internal fun formatDuration(totalSec: Int): String {
        if (totalSec <= 0) return ""
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

/** 登录 Cookie 失效时抛出，UI 据此提示「重新配置 Cookie」 */
internal class CookieLoginException(message: String) : Exception(message)
