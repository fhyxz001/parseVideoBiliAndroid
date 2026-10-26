package com.videoparser.bili

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToLong

class BiliViewModel(application: Application) : AndroidViewModel(application) {

    var input by mutableStateOf("")
    var isParsing by mutableStateOf(false)
    var video by mutableStateOf<BiliVideo?>(null)

    var isDownloading by mutableStateOf(false)
    var downloadPercent by mutableIntStateOf(0)
    var downloadedBytes by mutableLongStateOf(0L)
    var downloadTotalBytes by mutableLongStateOf(0L)
    var fileSize by mutableLongStateOf(0L)

    var isSavingCover by mutableStateOf(false)
    var showPlayer by mutableStateOf(false)

    /* ═════════════════════ 剪辑下载 ═════════════════════ */

    var showClipPage by mutableStateOf(false)
    var clipProbing by mutableStateOf(false)
    var clipDurationMs by mutableLongStateOf(0L)
    var clipStartMs by mutableLongStateOf(0L)
    var clipEndMs by mutableLongStateOf(0L)
    var isClipping by mutableStateOf(false)
    var clipPhaseText by mutableStateOf("")
    var clipPercent by mutableIntStateOf(0)
    var clipThumbs by mutableStateOf<List<ImageBitmap?>>(emptyList())
    var clipThumbsLoading by mutableStateOf(false)
    var clipPresetSec by mutableIntStateOf(DEFAULT_CLIP_PRESET_SEC)
    var clipCustomDuration by mutableStateOf(false)
    var clipResultFileName by mutableStateOf<String?>(null)
    var clipStartFrame by mutableStateOf<ImageBitmap?>(null)
    var clipEndFrame by mutableStateOf<ImageBitmap?>(null)
    var showFrameSaveDialog by mutableStateOf(false)
    var frameSaveIsStart by mutableStateOf(true)
    var isSavingFrame by mutableStateOf(false)

    private var clipProbe: ClipProbe? = null
    private var clipProbeKey: String? = null
    private var clipJob: Job? = null
    private var clipThumbsKey: String? = null

    // 取帧专用（单实例复用，串行取帧防止并发崩溃）
    private var frameRetriever: MediaMetadataRetriever? = null
    private var frameUrl: String? = null
    private var pendingStartMs: Long? = null
    private var pendingEndMs: Long? = null
    private var lastLoadedStartMs = Long.MIN_VALUE
    private var lastLoadedEndMs = Long.MIN_VALUE
    private val frameMutex = Mutex()

    val toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /* ═════════════════════ Cookie / 清晰度 ═════════════════════ */

    /** 设置页开关 */
    var showCookiePage by mutableStateOf(false)
        private set

    /** 是否已配置可用 Cookie */
    var loggedIn by mutableStateOf(false)
        private set

    /** 设置页里正在编辑的 Cookie 文本 */
    var cookieInput by mutableStateOf("")
        private set

    /** 是否已保存过 Cookie（决定「退出登录」是否可点） */
    var hasSavedCookie by mutableStateOf(false)
        private set

    /** 保存后的脱敏展示 */
    var cookieMask by mutableStateOf("")
        private set

    var cookieStatusMessage by mutableStateOf<String?>(null)
        private set

    var cookieError by mutableStateOf<String?>(null)
        private set

    init {
        loadCookieState()
    }

    private fun loadCookieState() {
        val app = getApplication<Application>()
        hasSavedCookie = BiliCookieStore.saved(app).isNotBlank()
        loggedIn = BiliCookieStore.load(app)
        cookieMask = BiliCookie.mask(BiliCookie.current)
    }

    /** 本次解析 / 下载要用的 Cookie（[BiliCookie.apply] 已补齐设备指纹 buvid3） */
    fun biliCookieHeader(): String = BiliCookie.current

    fun openCookiePage() {
        cookieInput = BiliCookieStore.saved(getApplication())
        cookieError = null
        cookieStatusMessage = null
        showCookiePage = true
    }

    fun closeCookiePage() {
        showCookiePage = false
        cookieError = null
        cookieStatusMessage = null
    }

    fun onCookieInputChanged(text: String) {
        cookieInput = text
        cookieError = null
        cookieStatusMessage = null
    }

    /** 从剪贴板读取 Cookie（不修改输入框以外的状态） */
    fun pasteCookieFromClipboard(): String? {
        val manager = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveCookie() {
        val raw = cookieInput.trim()
        if (raw.isEmpty()) {
            cookieError = "请先粘贴 Cookie"
            return
        }
        try {
            val saved = BiliCookieStore.save(getApplication(), raw)
            cookieInput = saved
            loggedIn = true
            hasSavedCookie = true
            cookieMask = BiliCookie.mask(BiliCookie.current)
            cookieError = null
            cookieStatusMessage = "Cookie 已保存，重新解析即可获取 1080P"
            toast("Cookie 已保存")
            // 已有解析结果时，自动按新 Cookie 重新解析一次
            if (video != null) {
                closeCookiePage()
                parse()
            }
        } catch (e: IllegalArgumentException) {
            cookieError = e.message ?: "Cookie 格式无法识别"
        }
    }

    fun clearCookie() {
        BiliCookieStore.clear(getApplication())
        cookieInput = ""
        loggedIn = false
        hasSavedCookie = false
        cookieMask = ""
        cookieError = null
        cookieStatusMessage = "已退出登录，清晰度将限制为 720P"
        toast("已清除 Cookie")
    }

    fun onInputChanged(value: String) {
        input = value
    }

    /** 从系统分享进来：填入文案并自动解析 */
    fun onSharedText(text: String) {
        if (text.isBlank()) return
        input = text
        parse()
    }

    /** 「粘贴并解析」：输入框有内容就直接解析，为空才读剪贴板 */
    fun pasteAndParse() {
        if (input.isNotBlank()) {
            parse()
            return
        }
        val manager = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板里没有内容")
            return
        }
        input = text
        parse()
    }

    /* ═════════════════════ 解析与下载 ═════════════════════ */

    private fun toast(message: String) {
        toastEvents.tryEmit(message)
    }

    /** 供 UI 直接触发的轻提示（如设置页粘贴失败） */
    fun showToast(message: String) = toast(message)

    fun parse() {
        if (isParsing) return
        val text = input.trim()
        if (text.isEmpty()) {
            toast("请先粘贴 B 站链接或 BV 号")
            return
        }

        isParsing = true
        video = null
        fileSize = 0L
        downloadPercent = 0
        downloadedBytes = 0L
        downloadTotalBytes = 0L
        showPlayer = false
        resetClipState()

        val cookie = biliCookieHeader()
        viewModelScope.launch {
            when (val result = BiliParser.parse(text, cookie)) {
                is BiliParseResult.Success -> {
                    val parsed = result.data
                    video = parsed
                    // 记住实际拿到的清晰度，下次解析直接从该档位请求
                    if (parsed.loggedIn) BiliCookieStore.saveQuality(getApplication(), parsed.quality)
                    val hint = when {
                        parsed.loggedIn && parsed.quality >= BiliParser.QUALITY_1080P ->
                            "解析成功 · ${parsed.qualityLabel}"
                        parsed.loggedIn ->
                            "当前视频最高 ${parsed.qualityLabel}（1080P 及以上需大会员或原视频不支持）"
                        parsed.quality > BiliParser.QUALITY_GUEST_MAX ->
                            "解析成功 · ${parsed.qualityLabel}"
                        else ->
                            "解析成功 · ${parsed.qualityLabel}（配置 Cookie 可解锁 1080P）"
                    }
                    toast(hint)
                    fetchFileSize(parsed.videoUrl)
                }
                is BiliParseResult.Error -> {
                    toast(result.message)
                    // 风控 / 登录失效：直接打开设置页引导配置 Cookie
                    if (result.message.contains("Cookie") || result.message.contains("风控")) {
                        openCookiePage()
                    }
                }
            }
            isParsing = false
        }
    }

    private fun fetchFileSize(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val size = VideoDownloader.fetchSize(url)
            // 防止异步返回时用户已经解析了新的视频
            if (video?.videoUrl == url) {
                fileSize = size
            }
        }
    }

    fun download() {
        if (isDownloading) return
        val data = video ?: return
        isDownloading = true
        downloadPercent = 0
        downloadedBytes = 0L
        downloadTotalBytes = 0L

        viewModelScope.launch {
            val ok = VideoDownloader.downloadVideo(
                context = getApplication(),
                url = data.videoUrl,
                title = data.title.ifBlank { "bilibili" },
                bvid = data.bvid
            ) { progress ->
                downloadPercent = progress.percent
                downloadedBytes = progress.downloadedBytes
                if (progress.totalBytes > 0) {
                    downloadTotalBytes = progress.totalBytes
                }
            }
            isDownloading = false
            toast(if (ok) "视频已保存到相册" else "下载失败，请重试")
        }
    }

    fun saveCover() {
        if (isSavingCover) return
        val data = video ?: return
        if (data.coverUrl.isBlank()) {
            toast("该视频没有封面")
            return
        }
        isSavingCover = true
        viewModelScope.launch {
            val ok = VideoDownloader.downloadCover(
                context = getApplication(),
                url = data.coverUrl,
                title = data.title.ifBlank { "bilibili" },
                bvid = data.bvid
            )
            isSavingCover = false
            toast(if (ok) "封面已保存到相册" else "封面保存失败")
        }
    }

    fun openPlayer() {
        if (video == null) {
            toast("请先解析视频")
            return
        }
        showPlayer = true
    }

    fun dismissPlayer() {
        showPlayer = false
    }

    fun copyTitle() {
        val title = video?.title
        if (title.isNullOrBlank()) {
            toast("没有可复制的标题")
            return
        }
        copyEvents.tryEmit(title)
        toast("标题已复制")
    }

    fun copyAuthor() {
        val name = video?.author?.name
        if (name.isNullOrBlank()) {
            toast("没有可复制的作者")
            return
        }
        copyEvents.tryEmit(name)
        toast("UP 主名称已复制")
    }

    /* ═════════════════════ 剪辑下载：状态与操作 ═════════════════════ */

    private fun resetClipState() {
        clipJob?.cancel()
        clipJob = null
        releaseFrameRetriever()
        clipProbe = null
        clipProbeKey = null
        clipThumbsKey = null
        clipProbing = false
        clipDurationMs = 0L
        clipStartMs = 0L
        clipEndMs = 0L
        clipThumbs = emptyList()
        clipThumbsLoading = false
        clipPresetSec = DEFAULT_CLIP_PRESET_SEC
        clipCustomDuration = false
        clipResultFileName = null
        isClipping = false
        clipPercent = 0
        clipPhaseText = ""
        showFrameSaveDialog = false
        isSavingFrame = false
    }

    fun openClipPage() {
        if (video == null) {
            toast("请先解析视频")
            return
        }
        showClipPage = true
        ensureClipProbe()
        ensureClipThumbs()
    }

    fun dismissClipPage() {
        if (isClipping) toast("剪辑仍在后台进行，完成后会提示")
        releaseFrameRetriever()
        showClipPage = false
    }

    /** 探测视频元数据（时长 + 采样索引），按 URL 缓存 */
    private fun ensureClipProbe() {
        val data = video ?: return
        val key = data.videoUrl.trim()
        if (key.isBlank()) return
        if (clipProbeKey == key && clipProbe != null) return
        clipProbeKey = key
        clipProbe = null
        clipDurationMs = 0L
        clipProbing = true
        viewModelScope.launch {
            val probed = VideoClipper.probe(key)
            if (clipProbeKey == key) {
                clipProbe = probed
                clipDurationMs = when (probed) {
                    is ClipProbe.Ready -> probed.durationMs
                    is ClipProbe.Fallback -> probed.durationMs
                }
                if (clipDurationMs > 0) {
                    clipPresetSec = DEFAULT_CLIP_PRESET_SEC
                    clipCustomDuration = false
                    clipStartMs = 0L
                    clipEndMs = minOf(clipDurationMs, DEFAULT_CLIP_PRESET_SEC * 1000L)
                    if (clipEndMs - clipStartMs < MIN_CLIP_SPAN_MS) {
                        clipStartMs = (clipEndMs - MIN_CLIP_SPAN_MS).coerceAtLeast(0L)
                    }
                }
                clipProbing = false
                ensureClipThumbs()
            }
        }
    }

    fun applyClipPreset(seconds: Int) {
        if (clipDurationMs <= 0 || seconds <= 0) return
        clipPresetSec = seconds
        clipCustomDuration = false
        applySpanMs(seconds * 1000L)
    }

    fun applyClipCustomDuration(seconds: Double) {
        if (clipDurationMs <= 0 || seconds <= 0) return
        clipPresetSec = 0
        clipCustomDuration = true
        applySpanMs((seconds * 1000).roundToLong())
    }

    private fun applySpanMs(spanMs: Long) {
        val span = spanMs.coerceAtLeast(MIN_CLIP_SPAN_MS)
        clipEndMs = (clipStartMs + span).coerceAtMost(clipDurationMs)
        if (clipEndMs - clipStartMs < MIN_CLIP_SPAN_MS) {
            clipStartMs = (clipEndMs - MIN_CLIP_SPAN_MS).coerceAtLeast(0L)
        }
    }

    fun setClipStartKeepingDuration(posMs: Long) {
        if (clipDurationMs <= 0) return
        val span = (clipEndMs - clipStartMs).coerceIn(MIN_CLIP_SPAN_MS, clipDurationMs)
        val s = posMs.coerceIn(0L, (clipDurationMs - span).coerceAtLeast(0L))
        clipStartMs = s
        clipEndMs = (s + span).coerceAtMost(clipDurationMs)
        if (clipEndMs - clipStartMs < MIN_CLIP_SPAN_MS) {
            clipStartMs = (clipEndMs - MIN_CLIP_SPAN_MS).coerceAtLeast(0L)
        }
    }

    fun setClipEndManual(posMs: Long) {
        if (clipDurationMs <= 0) return
        var e = posMs.coerceIn(0L, clipDurationMs)
        if (e - clipStartMs < MIN_CLIP_SPAN_MS) {
            e = (clipStartMs + MIN_CLIP_SPAN_MS).coerceAtMost(clipDurationMs)
            if (e - clipStartMs < MIN_CLIP_SPAN_MS) {
                clipStartMs = (e - MIN_CLIP_SPAN_MS).coerceAtLeast(0L)
            }
        }
        clipEndMs = e
        val spanSec = (e - clipStartMs) / 1000.0
        val matched = CLIP_PRESET_SECONDS.firstOrNull { kotlin.math.abs(it - spanSec) < 0.4 }
        if (matched != null) {
            clipPresetSec = matched
            clipCustomDuration = false
        } else {
            clipPresetSec = 0
            clipCustomDuration = true
        }
    }

    /** 当前实际截取时长（秒） */
    val clipSelectedSeconds: Double
        get() = (clipEndMs - clipStartMs).coerceAtLeast(0L) / 1000.0

    fun startClipDownload() {
        if (isClipping) return
        val data = video ?: return
        val url = data.videoUrl.trim()
        val probe = clipProbe
        if (url.isBlank() || probe == null) {
            toast("视频信息未就绪，请稍候")
            return
        }
        if (clipDurationMs <= 0) {
            toast("无法获取视频时长，暂不支持剪辑")
            return
        }
        if (clipEndMs - clipStartMs < MIN_CLIP_SPAN_MS) {
            toast("请选择至少 1 秒的片段")
            return
        }
        isClipping = true
        clipPercent = 0
        clipPhaseText = "准备剪辑..."
        clipJob = viewModelScope.launch {
            try {
                val result = VideoClipper.clipToGallery(
                    getApplication(),
                    url,
                    data.title.ifBlank { "bilibili" },
                    clipStartMs,
                    clipEndMs,
                    probe,
                    onPhase = { clipPhaseText = phaseText(it) },
                    onPercent = { clipPercent = it }
                )
                when (result) {
                    is ClipResult.Success -> {
                        clipResultFileName = result.fileName
                        toast("剪辑已保存到相册")
                    }
                    is ClipResult.Failure -> toast(result.message)
                }
            } catch (e: CancellationException) {
                toast("已取消剪辑")
                throw e
            } finally {
                isClipping = false
                clipPhaseText = ""
            }
        }
    }

    fun cancelClip() {
        clipJob?.cancel()
        clipJob = null
    }

    fun dismissClipResult() {
        clipResultFileName = null
        showClipPage = false
    }

    private fun phaseText(phase: ClipPhase): String = when (phase) {
        ClipPhase.DOWNLOADING_SEGMENT -> "正在下载所选片段..."
        ClipPhase.BUILDING -> "正在生成剪辑文件..."
        ClipPhase.DOWNLOADING_FULL -> "服务器不支持分段下载，正在下载完整视频..."
        ClipPhase.TRIMMING -> "正在剪切视频..."
        ClipPhase.SAVING -> "正在保存到相册..."
    }

    /* ═════════════════════ 剪辑：缩略图与首尾帧 ═════════════════════ */

    fun requestClipFrame(isStart: Boolean, timeMs: Long) {
        if (isStart) {
            if (timeMs == lastLoadedStartMs || timeMs == pendingStartMs) return
            pendingStartMs = timeMs
        } else {
            if (timeMs == lastLoadedEndMs || timeMs == pendingEndMs) return
            pendingEndMs = timeMs
        }
        runClipFrameLoader()
    }

    private fun runClipFrameLoader() {
        val url = video?.videoUrl?.trim() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            frameMutex.withLock {
                while (true) {
                    val startTarget = pendingStartMs
                    val endTarget = pendingEndMs
                    if (startTarget == null && endTarget == null) break
                    val retriever = ensureFrameRetriever(url) ?: break
                    if (startTarget != null) {
                        pendingStartMs = null
                        val bmp = getFrame(retriever, startTarget, PREVIEW_TARGET_WIDTH)
                        if (bmp != null && pendingStartMs == null) {
                            clipStartFrame = scaleDown(bmp, PREVIEW_TARGET_WIDTH).asImageBitmap()
                            lastLoadedStartMs = startTarget
                        }
                    }
                    if (endTarget != null) {
                        pendingEndMs = null
                        val bmp = getFrame(retriever, endTarget, PREVIEW_TARGET_WIDTH)
                        if (bmp != null && pendingEndMs == null) {
                            clipEndFrame = scaleDown(bmp, PREVIEW_TARGET_WIDTH).asImageBitmap()
                            lastLoadedEndMs = endTarget
                        }
                    }
                }
            }
        }
    }

    private suspend fun getFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetWidth
                )
            } else {
                retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureFrameRetriever(url: String): MediaMetadataRetriever? {
        if (frameRetriever != null && frameUrl == url) return frameRetriever
        releaseFrameRetriever()
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(url, mediaHeaders())
            frameRetriever = retriever
            frameUrl = url
            retriever
        } catch (e: Exception) {
            frameRetriever = null
            null
        }
    }

    private fun releaseFrameRetriever() {
        pendingStartMs = null
        pendingEndMs = null
        lastLoadedStartMs = Long.MIN_VALUE
        lastLoadedEndMs = Long.MIN_VALUE
        val retriever = frameRetriever
        frameRetriever = null
        frameUrl = null
        clipStartFrame = null
        clipEndFrame = null
        runCatching { retriever?.release() }
    }

    fun onClipFrameClicked(isStart: Boolean) {
        val frame = if (isStart) clipStartFrame else clipEndFrame
        if (frame == null) {
            toast("画面加载中，请稍候")
            return
        }
        frameSaveIsStart = isStart
        showFrameSaveDialog = true
    }

    fun dismissFrameSaveDialog() {
        showFrameSaveDialog = false
    }

    fun confirmSaveFrame() {
        if (isSavingFrame) return
        val bitmap = (if (frameSaveIsStart) clipStartFrame else clipEndFrame)
            ?.asAndroidBitmap() ?: return
        showFrameSaveDialog = false
        isSavingFrame = true
        viewModelScope.launch {
            val title = video?.title?.ifBlank { "bilibili" } ?: "bilibili"
            val fileName = frameFileName(
                title,
                frameSaveIsStart,
                if (frameSaveIsStart) clipStartMs else clipEndMs
            )
            val ok = VideoDownloader.saveBitmapToGallery(getApplication(), bitmap, fileName)
            isSavingFrame = false
            toast(if (ok) "已保存到相册" else "保存失败")
        }
    }

    private fun frameFileName(title: String, isStart: Boolean, timeMs: Long): String {
        val base = title.replace(Regex("""[\\/:*?"<>|]"""), "").ifBlank { "bilibili" }.take(60)
        val side = if (isStart) "首帧" else "尾帧"
        val totalSec = timeMs.coerceAtLeast(0L) / 1000
        val t = String.format(
            Locale.US, "%02d%02d%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60
        )
        return "${base}_剪辑${side}_$t.jpg"
    }

    private fun ensureClipThumbs() {
        val data = video ?: return
        val key = data.videoUrl.trim()
        if (key.isBlank()) return
        if (clipThumbsKey == key && clipThumbs.isNotEmpty()) return
        if (clipDurationMs <= 0) return
        clipThumbsKey = key
        clipThumbsLoading = true
        clipThumbs = List(CLIP_THUMB_COUNT) { null }
        viewModelScope.launch(Dispatchers.IO) {
            val frames = loadClipThumbnails(key, clipDurationMs, CLIP_THUMB_COUNT)
            if (clipThumbsKey == key) {
                clipThumbs = frames
                clipThumbsLoading = false
            }
        }
    }

    private suspend fun loadClipThumbnails(
        url: String,
        durationMs: Long,
        count: Int
    ): List<ImageBitmap?> = coroutineScope {
        val retrievers = (0 until THUMB_WORKERS).map { MediaMetadataRetriever() }
        try {
            retrievers.forEach { it.setDataSource(url, mediaHeaders()) }
            val results = arrayOfNulls<ImageBitmap>(count)
            (0 until THUMB_WORKERS).map { worker ->
                async {
                    val retriever = retrievers[worker]
                    for (i in worker until count step THUMB_WORKERS) {
                        val posMs = (durationMs * (i + 0.5) / count).toLong()
                        val frame = getFrame(retriever, posMs, THUMB_TARGET_WIDTH)
                        val scaled =
                            if (frame != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                                scaleDown(frame, THUMB_TARGET_WIDTH)
                            } else {
                                frame
                            }
                        results[i] = scaled?.asImageBitmap()
                        clipThumbs = results.toList()
                    }
                }
            }.awaitAll()
            results.toList()
        } catch (e: Exception) {
            List(count) { null }
        } finally {
            retrievers.forEach { runCatching { it.release() } }
        }
    }

    private fun scaleDown(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap,
            targetWidth,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun mediaHeaders(): HashMap<String, String> = VideoDownloader.mediaHeaders()

    companion object {
        private const val MIN_CLIP_SPAN_MS = 1000L

        /** 默认快捷截取时长（秒） */
        const val DEFAULT_CLIP_PRESET_SEC = 20

        /** 快捷截取时长选项（秒） */
        val CLIP_PRESET_SECONDS = listOf(20, 60, 120)

        private const val CLIP_THUMB_COUNT = 16
        private const val THUMB_WORKERS = 3
        private const val THUMB_TARGET_WIDTH = 320
        private const val PREVIEW_TARGET_WIDTH = 480
    }
}
