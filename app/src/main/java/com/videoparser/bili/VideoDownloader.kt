package com.videoparser.bili

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

/**
 * 保存视频 / 封面到系统相册。
 *
 * - Android 10+：走 MediaStore，免存储权限
 * - Android 9 及以下：写 DCIM/BiliParser、Pictures/BiliParser，需要 WRITE_EXTERNAL_STORAGE
 */
object VideoDownloader {

    private const val FOLDER = "BiliParser"

    suspend fun fetchSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val head = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .build()
            BiliParser.http.newCall(head).execute().use { response ->
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (response.isSuccessful && length > 0) return@withContext length
            }

            // 部分 CDN 不支持 HEAD，退化为 Range 请求只取 1 字节
            val range = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .build()
            BiliParser.http.newCall(range).execute().use { response ->
                response.header("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun downloadVideo(
        context: Context,
        url: String,
        title: String,
        bvid: String,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = buildFileName(title, bvid) + ".mp4"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .build()

            BiliParser.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: 0L
                var copied = 0L
                var lastEmit = 0L

                val ok = saveToGallery(
                    context = context,
                    fileName = fileName,
                    mimeType = "video/mp4",
                    publicDir = Environment.DIRECTORY_DCIM
                ) { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) {
                                lastEmit = now
                                val percent = if (total > 0) {
                                    (copied * 100 / total).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                onProgress(DownloadProgress(percent, copied, total))
                            }
                        }
                    }
                }

                if (ok) {
                    onProgress(DownloadProgress(100, copied, if (total > 0) total else copied))
                }
                ok
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun downloadCover(context: Context, url: String, title: String, bvid: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val fileName = buildFileName(title, bvid) + "_封面.jpg"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", BiliParser.USER_AGENT)
                    .header("Referer", BiliParser.REFERER)
                    .build()
                BiliParser.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    saveToGallery(
                        context = context,
                        fileName = fileName,
                        mimeType = "image/jpeg",
                        publicDir = Environment.DIRECTORY_PICTURES
                    ) { output -> body.byteStream().copyTo(output) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /* ═══════════════════ 剪辑功能使用的底层能力 ═══════════════════ */

    suspend fun fetchVideoSize(url: String): Long = fetchSize(url)

    /** 由调用方直接向相册输出流写入视频数据（剪辑功能使用） */
    suspend fun saveVideoToGallery(
        context: Context,
        fileName: String,
        writer: (OutputStream) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        saveToGallery(context, fileName, "video/mp4", Environment.DIRECTORY_DCIM, writer)
    }

    suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            saveToGallery(context, fileName, "image/jpeg", Environment.DIRECTORY_PICTURES) { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
        }

    /** 只下载 [rangeStart, rangeEndExclusive) 字节；服务器不支持 Range 时返回 false */
    suspend fun downloadRangeToFile(
        url: String,
        rangeStart: Long,
        rangeEndExclusive: Long,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (rangeEndExclusive <= rangeStart) return@withContext false
        val total = rangeEndExclusive - rangeStart
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=$rangeStart-${rangeEndExclusive - 1}")
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .build()
            BiliParser.http.newCall(request).execute().use { response ->
                if (response.code != 206) return@withContext false
                val body = response.body ?: return@withContext false
                var downloaded = 0L
                var lastEmit = 0L
                dest.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("range download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) {
                                lastEmit = now
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                onProgress(downloaded, total)
                dest.length() == total
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** 全量下载到缓存文件（剪辑降级路径使用） */
    suspend fun downloadWholeToFile(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BiliParser.USER_AGENT)
                .header("Referer", BiliParser.REFERER)
                .build()
            BiliParser.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: 0L
                var downloaded = 0L
                var lastEmit = 0L
                dest.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("full download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) {
                                lastEmit = now
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                onProgress(downloaded, total)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** 文件名：标题（去掉非法字符、截断）_BV号 */
    internal fun buildFileName(title: String, bvid: String): String {
        val base = title
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
            .take(60)
            .ifBlank { "bilibili" }
        return if (bvid.isBlank()) base else "${base}_$bvid"
    }

    private fun saveToGallery(
        context: Context,
        fileName: String,
        mimeType: String,
        publicDir: String,
        writer: (OutputStream) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (mimeType.startsWith("video/")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return false
            return try {
                val output = resolver.openOutputStream(uri)
                if (output == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                output.use(writer)
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                if (e is CancellationException) throw e
                false
            }
        }

        // Android 9 及以下：直接写公共目录
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(publicDir)
        val dir = File(root, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, fileName)
        return try {
            file.outputStream().use(writer)
            true
        } catch (e: Exception) {
            file.delete()
            if (e is CancellationException) throw e
            false
        }
    }
}
