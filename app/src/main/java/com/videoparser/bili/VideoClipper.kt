package com.videoparser.bili

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale

/**
 * 剪辑编排层：
 *
 * 1. probe()      —— 用少量字节探测视频元数据（moov），拿到时长与"时间→字节"索引；
 * 2. clipToGallery—— 按所选时间段只下载对应字节区间，流复制重建 MP4 存入相册；
 *                    若服务器不支持 Range / 解析失败（分片 MP4 等），自动降级为
 *                    "全量下载 + 系统 MediaExtractor/MediaMuxer 剪切"。
 */
sealed class ClipProbe {
    /** 流剪辑可用：已解析出完整采样索引 */
    class Ready(
        val parsed: ParsedMp4,
        val durationMs: Long,
        val fileSize: Long
    ) : ClipProbe()

    /** 走降级路径；durationMs 为 -1 表示无法得知时长 */
    class Fallback(
        val durationMs: Long,
        val fileSize: Long,
        val reason: String
    ) : ClipProbe()
}

sealed class ClipResult {
    class Success(val fileName: String) : ClipResult()
    class Failure(val message: String) : ClipResult()
}

/** 剪辑各阶段（UI 据此显示文案） */
enum class ClipPhase {
    DOWNLOADING_SEGMENT,  // 正在只下载所需片段
    BUILDING,             // 正在生成剪辑文件
    DOWNLOADING_FULL,     // 正在下载完整视频（降级路径）
    TRIMMING,             // 正在剪切视频（降级路径）
    SAVING                // 正在保存到相册
}

object VideoClipper {

    /** 头部探测字节数：绝大多数短视频的 moov 都在其中 */
    private const val HEAD_BYTES = 768 * 1024

    /** moov 在尾部时的探测字节数（1 小时视频的 moov 约 4~8MB） */
    private const val TAIL_BYTES = 16L * 1024 * 1024

    /** 探测视频元数据。永不抛异常，失败一律转 Fallback。 */
    suspend fun probe(url: String): ClipProbe = withContext(Dispatchers.IO) {
        val fileSize = VideoDownloader.fetchVideoSize(url.trim())
        try {
            // 头部拉取：无论是否支持 Range 都能读到开头一段
            val head = fetchHead(url, HEAD_BYTES)
            val scan = Mp4FileLocator.scanHead(head)

            if (scan.hasMoof) {
                return@withContext ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "分片式 MP4")
            }

            // 头部 moov 完整 -> 直接解析
            scan.moovBox?.let { moov ->
                return@withContext try {
                    val parsed = Mp4Parser.parse(scan.ftypBox ?: ByteArray(0), moov)
                    toReady(parsed, fileSize)
                } catch (e: Exception) {
                    ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "元数据解析失败")
                }
            }

            // moov 被截断：需要精确补拉，必须支持 Range
            if (scan.moovStart >= 0 && scan.moovSize > 0 && supportsRange(url)) {
                val moovBytes = fetchExactRange(url, scan.moovStart, scan.moovSize)
                if (moovBytes != null) {
                    return@withContext try {
                        val parsed = Mp4Parser.parse(scan.ftypBox ?: ByteArray(0), moovBytes)
                        toReady(parsed, fileSize)
                    } catch (e: Exception) {
                        ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "元数据解析失败")
                    }
                }
            }

            // moov 可能整个在尾部：从文件末尾搜索
            if (fileSize > 0 && supportsRange(url)) {
                val tailStart = maxOf(0L, fileSize - TAIL_BYTES)
                val tail = fetchExactRange(url, tailStart, fileSize - tailStart)
                val located = tail?.let { Mp4FileLocator.locateMoovInTail(it, tailStart, fileSize) }
                if (tail != null && located != null) {
                    val (moovStart, moovSize) = located
                    // moov 已完整落在尾部缓冲内时直接切片，省去一次数 MB 的重复下载
                    val moovBytes = if (moovStart + moovSize <= tailStart + tail.size) {
                        val from = (moovStart - tailStart).toInt()
                        Mp4BoxIo.copy(tail, from, (from + moovSize).toInt())
                    } else {
                        fetchExactRange(url, moovStart, moovSize)
                    }
                    if (moovBytes != null) {
                        return@withContext try {
                            val parsed = Mp4Parser.parse(
                                scan.ftypBox ?: ByteArray(0),
                                moovBytes
                            )
                            toReady(parsed, fileSize)
                        } catch (e: Exception) {
                            ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "元数据解析失败")
                        }
                    }
                }
            }

            ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "无法定位视频元数据")
        } catch (e: Exception) {
            ClipProbe.Fallback(frameworkDurationMs(url), fileSize, "网络异常")
        }
    }

    /** 关键帧吸附后的实际窗口（Ready 时给出真实值，供 UI 预览） */
    fun snappedWindowMs(probe: ClipProbe, startMs: Long, endMs: Long): Pair<Long, Long> =
        if (probe is ClipProbe.Ready) {
            try {
                Mp4Clipper.snappedWindowMs(probe.parsed, startMs, endMs)
            } catch (e: Exception) {
                startMs to endMs
            }
        } else {
            startMs to endMs
        }

    /**
     * 剪辑并保存到相册。onPhase 报告阶段，onPercent 报告 0~100 的整体进度。
     * 通过取消外层协程即可取消（内部循环会检查协程活跃状态）。
     */
    suspend fun clipToGallery(
        context: Context,
        url: String,
        title: String,
        startMs: Long,
        endMs: Long,
        probe: ClipProbe,
        onPhase: (ClipPhase) -> Unit,
        onPercent: (Int) -> Unit
    ): ClipResult = withContext(Dispatchers.IO) {
        val safeUrl = url.trim()
        try {
            when (probe) {
                is ClipProbe.Ready -> clipByRange(context, safeUrl, title, startMs, endMs, probe, onPhase, onPercent)
                is ClipProbe.Fallback -> clipByFullDownload(context, safeUrl, title, startMs, endMs, probe, onPhase, onPercent)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Mp4ClipException) {
            ClipResult.Failure(e.message ?: "剪辑失败")
        } catch (e: Exception) {
            ClipResult.Failure("剪辑失败，请重试")
        }
    }

    /* ═══════════════ 路径 A：Range 片段下载 + 流复制重建 ═══════════════ */

    private suspend fun clipByRange(
        context: Context,
        url: String,
        title: String,
        startMs: Long,
        endMs: Long,
        probe: ClipProbe.Ready,
        onPhase: (ClipPhase) -> Unit,
        onPercent: (Int) -> Unit
    ): ClipResult {
        val plan = try {
            Mp4Clipper.planMs(probe.parsed, startMs, endMs)
        } catch (e: Mp4ClipException) {
            return ClipResult.Failure(e.message ?: "所选时间段无效")
        }
        if (probe.fileSize > 0 && plan.rangeEndExclusive > probe.fileSize) {
            return ClipResult.Failure("采样表超出文件范围")
        }

        val segment = File(context.cacheDir, "clip_seg_${System.currentTimeMillis()}.bin")
        try {
            onPhase(ClipPhase.DOWNLOADING_SEGMENT)
            val ok = downloadRangeToFile(
                url, plan.rangeStart, plan.rangeEndExclusive, segment
            ) { downloaded ->
                val p = if (plan.rangeSize > 0) (downloaded * 85 / plan.rangeSize).toInt() else 0
                onPercent(p.coerceIn(0, 85))
            }
            if (!ok || segment.length() != plan.rangeSize) {
                // Range 下载不完整：转降级路径重试
                return clipByFullDownload(
                    context, url, title, startMs, endMs,
                    ClipProbe.Fallback(probe.durationMs, probe.fileSize, "片段下载不完整"),
                    onPhase, onPercent
                )
            }

            onPhase(ClipPhase.BUILDING)
            val fileName = clipFileName(title, startMs, endMs)
            val clipContext = coroutineContext
            val saved = VideoDownloader.saveVideoToGallery(context, fileName) { out ->
                RandomAccessFile(segment, "r").use { raf ->
                    Mp4Clipper.writeClip(out, probe.parsed, plan, copySegment = { absoluteOffset: Long, length: Long, dest: OutputStream ->
                        clipContext.ensureActive() // 让重建阶段也能响应取消
                        raf.seek(absoluteOffset - plan.rangeStart)
                        copy(raf, dest, length)
                    })
                }
            }
            onPercent(100)
            return if (saved) ClipResult.Success(fileName)
            else ClipResult.Failure("保存到相册失败")
        } finally {
            segment.delete()
        }
    }

    /* ═══════════════ 路径 B：全量下载 + 系统剪切（保底） ═══════════════ */

    private suspend fun clipByFullDownload(
        context: Context,
        url: String,
        title: String,
        startMs: Long,
        endMs: Long,
        probe: ClipProbe.Fallback,
        onPhase: (ClipPhase) -> Unit,
        onPercent: (Int) -> Unit
    ): ClipResult {
        val source = File(context.cacheDir, "clip_full_${System.currentTimeMillis()}.mp4")
        val output = File(context.cacheDir, "clip_out_${System.currentTimeMillis()}.mp4")
        try {
            onPhase(ClipPhase.DOWNLOADING_FULL)
            val ok = VideoDownloader.downloadWholeToFile(url, source) { downloaded, total ->
                val denom = if (total > 0) total else probe.fileSize
                val p = if (denom > 0) (downloaded * 70 / denom).toInt() else 0
                onPercent(p.coerceIn(0, 70))
            }
            if (!ok || source.length() == 0L) return ClipResult.Failure("视频下载失败")

            onPhase(ClipPhase.TRIMMING)
            val trimmed = trimWithMediaExtractor(
                source, startMs * 1000, endMs * 1000, output
            ) { fraction ->
                onPercent((70 + fraction * 20).toInt().coerceIn(70, 90))
            }
            if (!trimmed || output.length() == 0L) return ClipResult.Failure("视频剪切失败")

            onPhase(ClipPhase.SAVING)
            val fileName = clipFileName(title, startMs, endMs)
            val clipContext = coroutineContext
            val saved = VideoDownloader.saveVideoToGallery(context, fileName) { out ->
                output.inputStream().use { input ->
                    var copied = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        clipContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        onPercent((90 + copied * 10 / output.length().coerceAtLeast(1)).toInt().coerceIn(90, 99))
                    }
                }
            }
            onPercent(100)
            return if (saved) ClipResult.Success(fileName)
            else ClipResult.Failure("保存到相册失败")
        } finally {
            source.delete()
            output.delete()
        }
    }

    /** 用系统 MediaExtractor/MediaMuxer 做无损剪切（关键帧对齐起点） */
    private fun trimWithMediaExtractor(
        input: File,
        startUs: Long,
        endUs: Long,
        output: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            var rotation = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    if (mime.startsWith("video/") && format.containsKey("rotation-degrees")) {
                        rotation = format.getInteger("rotation-degrees")
                    }
                    indexMap[i] = muxer.addTrack(format)
                }
            }
            if (indexMap.isEmpty()) return false
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var lastReport = 0f
            val window = (endUs - startUs).coerceAtLeast(1)
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                val dst = indexMap[trackIndex]
                if (dst != null) {
                    info.presentationTimeUs = pts
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dst, buffer, info)
                }
                extractor.advance()
                val frac = ((pts - startUs).toFloat() / window).coerceIn(0f, 1f)
                if (frac - lastReport > 0.02f) {
                    lastReport = frac
                    onProgress(frac)
                }
            }
            muxer.stop()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    /* ═══════════════ 网络与 IO 基础 ═══════════════ */

    private fun toReady(parsed: ParsedMp4, fileSize: Long): ClipProbe {
        val durationMs = parsed.durationMs
        if (durationMs <= 0) return ClipProbe.Fallback(-1L, fileSize, "无有效时长")
        return ClipProbe.Ready(parsed, durationMs, fileSize)
    }

    /** 拉取文件头部若干字节（无论服务器是否支持 Range） */
    private fun fetchHead(url: String, bytes: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${bytes - 1}")
            .header("User-Agent", BiliParser.USER_AGENT)
            .header("Referer", BiliParser.REFERER)
            .header("Origin", "https://www.bilibili.com")
            .get()
            .build()
        BiliParser.http.newCall(request).execute().use { response ->
            val body = response.body ?: return ByteArray(0)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (out.size() < bytes) {
                    val n = input.read(buf, 0, minOf(buf.size, bytes - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    /** 精确拉取 [start, start+length) 字节；失败返回 null */
    private fun fetchExactRange(url: String, start: Long, length: Long): ByteArray? {
        if (length <= 0) return null
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("User-Agent", BiliParser.USER_AGENT)
            .header("Referer", BiliParser.REFERER)
            .header("Origin", "https://www.bilibili.com")
            .get()
            .build()
        BiliParser.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val out = java.io.ByteArrayOutputStream(length.coerceAtMost(64L * 1024 * 1024).toInt())
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (out.size() < length) {
                    val n = input.read(buf, 0, minOf(buf.size, (length - out.size()).toInt()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return if (out.size() == length.toInt()) out.toByteArray() else null
        }
    }

    /** 是否支持 Range 请求（探测 bytes=0-0 是否返回 206） */
    private fun supportsRange(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .header("Origin", "https://www.bilibili.com")
                .head()
                .build()
            BiliParser.http.newCall(request).execute().use { response ->
                response.code == 206 ||
                    response.header("Content-Range") != null
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Range 下载片段到文件：大片段优先多线程分片，失败（含服务器不支持 Range）回退单流探测 */
    private suspend fun downloadRangeToFile(
        url: String,
        rangeStart: Long,
        rangeEndExclusive: Long,
        dest: File,
        onDownloaded: (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (VideoDownloader.downloadRangeToFile(
                url, rangeStart, rangeEndExclusive, dest
            ) { downloaded, _ -> onDownloaded(downloaded) }
        ) {
            return@withContext true
        }
        dest.delete()
        val context = coroutineContext
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$rangeStart-${rangeEndExclusive - 1}")
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .header("Origin", "https://www.bilibili.com")
                .get()
                .build()
            BiliParser.http.newCall(request).execute().use { response ->
                if (response.code != 206) return@withContext false // 服务器不支持 Range
                val body = response.body ?: return@withContext false
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            onDownloaded(downloaded)
                        }
                    }
                }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun copy(raf: RandomAccessFile, out: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** 剪辑产物文件名：标题_剪辑_起止时间.mp4 */
    fun clipFileName(title: String, startMs: Long, endMs: Long): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "video" }.take(60)
        return "${base}_剪辑_${fmtMs(startMs)}-${fmtMs(endMs)}.mp4"
    }

    private fun fmtMs(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(
            Locale.US, "%02d%02d%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60
        )
    }

    /** 用系统 MediaMetadataRetriever 获取时长（毫秒），失败返回 -1 */
    private fun frameworkDurationMs(url: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, VideoDownloader.mediaHeaders())
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
