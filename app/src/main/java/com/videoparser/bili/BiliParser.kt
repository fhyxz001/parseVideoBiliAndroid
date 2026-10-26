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
    val author: BiliAuthor
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

internal data class PlayInfo(val url: String, val quality: Int)

/**
 * B 站解析器：只依赖 B 站公开 Web 接口，不携带 Cookie、不需要登录。
 *
 * 流程与 python 版 parse-video-py 的 BiliBili 解析器一致：
 *   分享链接 / BV 号 / av 号
 *     -> (b23.tv 短链跟随跳转)
 *     -> api.bilibili.com/x/web-interface/view   拿标题、封面、UP 主、cid
 *     -> api.bilibili.com/x/player/playurl     拿 mp4 直链（platform=html5）
 */
object BiliParser {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com/"

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

    suspend fun parse(rawInput: String): BiliParseResult = withContext(Dispatchers.IO) {
        try {
            val input = rawInput.trim()
            if (input.isEmpty()) {
                return@withContext BiliParseResult.Error("请先粘贴 B 站链接或 BV 号")
            }

            var link = extractUrl(input)
            var id = link?.let(::parseVideoId) ?: parseVideoId(input)

            if (id == null && link != null && isShortLink(link)) {
                link = resolveRedirect(link)
                id = parseVideoId(link)
            }
            if (id == null) {
                return@withContext BiliParseResult.Error("没有识别到 B 站视频，请检查分享链接或 BV 号")
            }

            val view = fetchView(id)
            val play = fetchPlayUrl(view)

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
                    author = view.author
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

    private fun fetchView(id: VideoId): ViewInfo {
        val query = id.bvid?.let { "bvid=$it" } ?: "aid=${id.aid}"
        val json = getJson("https://api.bilibili.com/x/web-interface/view?$query")
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(apiErrorMessage(code, json.optString("message")))
        }
        val data = json.optJSONObject("data") ?: throw BiliException("B 站返回数据为空")
        return mapView(data)
    }

    private fun fetchPlayUrl(view: ViewInfo): PlayInfo {
        val apiUrl = buildString {
            append("https://api.bilibili.com/x/player/playurl?otype=json&fnver=0&fnval=0&qn=80")
            append("&bvid=").append(view.bvid)
            append("&cid=").append(view.cid)
            append("&platform=html5")
        }
        val json = getJson(apiUrl)
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliException(apiErrorMessage(code, json.optString("message")))
        }
        val data = json.optJSONObject("data") ?: throw BiliException("没有获取到播放信息")
        return mapPlayUrl(data)
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        http.newCall(request).execute().use { response ->
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

    internal fun mapPlayUrl(data: JSONObject): PlayInfo {
        val first = data.optJSONArray("durl")?.optJSONObject(0)
        val link = first?.optString("url").orEmpty()
        if (link.isBlank()) {
            throw BiliException("该视频没有可用的 MP4 地址（番剧、付费或互动视频暂不支持）")
        }
        return PlayInfo(url = normalizeVideoUrl(link), quality = data.optInt("quality", 0))
    }

    /* ───────────────────────── 展示与文案 ───────────────────────── */

    internal fun apiErrorMessage(code: Int, message: String?): String = when (code) {
        -400 -> "请求参数错误"
        -403 -> "访问被拒绝，可能存在地区限制"
        -404 -> "视频不存在或已被删除"
        -412 -> "请求被 B 站风控拦截，请稍后再试"
        -352 -> "触发 B 站风控校验，请稍后再试"
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
