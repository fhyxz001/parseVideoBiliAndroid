package com.videoparser.bili

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

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
    var clipResultFileName by mutableStateOf<String?>(null)

    private var clipProbe: ClipProbe? = null
    private var clipProbeKey: String? = null
    private var clipJob: Job? = null

    val toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

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

        viewModelScope.launch {
            when (val result = BiliParser.parse(text)) {
                is BiliParseResult.Success -> {
                    video = result.data
                    toast("解析成功 · ${result.data.qualityLabel}")
                    fetchFileSize(result.data.videoUrl)
                }
                is BiliParseResult.Error -> {
                    toast(result.message)
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
        clipProbe = null
        clipProbeKey = null
        clipProbing = false
        clipDurationMs = 0L
        clipStartMs = 0L
        clipEndMs = 0L
        clipResultFileName = null
        isClipping = false
        clipPercent = 0
        clipPhaseText = ""
    }

    fun openClipPage() {
        if (video == null) {
            toast("请先解析视频")
            return
        }
        showClipPage = true
        ensureClipProbe()
    }

    fun dismissClipPage() {
        if (isClipping) toast("剪辑仍在后台进行，完成后会提示")
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
                    // 开始时间与截取时长均由用户输入（输入框默认留空），内部先置零
                    clipStartMs = 0L
                    clipEndMs = 0L
                }
                clipProbing = false
            }
        }
    }

    /**
     * 调整截取时长（时/分/秒输入）：保持开始时间不变，结束时间联动。
     * 超出视频末尾时截到末尾，实际时长随之缩短；输入为空时时长为 0。
     */
    fun setClipDurationKeepingStart(durationMs: Long) {
        if (clipDurationMs <= 0) return
        if (durationMs <= 0L) {
            clipEndMs = clipStartMs
            return
        }
        clipEndMs = (clipStartMs + durationMs).coerceAtMost(clipDurationMs)
    }

    /**
     * 调整开始时间（时/分/秒输入）：保持当前截取时长，结束时间联动。
     * 剩余长度不足当前时长时，结束时间贴到视频末尾，实际时长随之缩短。
     */
    fun setClipStartKeepingDuration(posMs: Long) {
        if (clipDurationMs <= 0) return
        // 视频本身不足 1 秒时，下限退化为视频时长，避免 coerceIn 区间非法
        val minSpan = minOf(MIN_CLIP_SPAN_MS, clipDurationMs)
        val currentSpan = (clipEndMs - clipStartMs).coerceAtLeast(0L)
        // 时长为空（0）时保持为空，不强行补足最小时长
        val span = if (currentSpan == 0L) 0L else currentSpan.coerceIn(minSpan, clipDurationMs)
        val s = posMs.coerceIn(0L, (clipDurationMs - span).coerceAtLeast(0L))
        clipStartMs = s
        clipEndMs = (s + span).coerceAtMost(clipDurationMs)
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

    companion object {
        private const val MIN_CLIP_SPAN_MS = 1000L
    }
}
