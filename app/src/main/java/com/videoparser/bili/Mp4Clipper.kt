package com.videoparser.bili

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 剪辑核心：按时间窗规划每个轨道要保留的样本，并用下载到的片段字节重建一个合法的 MP4 容器。
 *
 * 原理：moov 里的采样表记录了每个样本（帧/音频包）的时间与文件偏移。
 * 1) 视频起点吸附到目标时间之前最近的关键帧（stss），保证可独立解码；
 * 2) 对所有轨道求出同一时间窗内样本的字节区间，仅用 HTTP Range 下载这一段；
 * 3) 裁剪采样表、把 chunk 偏移重定位到新文件，重建 ftyp+moov+mdat（faststart）。
 * 全程流复制、不转码。纯 JVM 实现，可直接单元测试。
 */

class Mp4ClipException(message: String) : Exception(message)

/** 单个轨道在剪辑窗口内保留的样本区间 [fromSample, toSampleExclusive) */
class TrackClip internal constructor(
    val track: Mp4Track,
    val fromSample: Int,
    val toSampleExclusive: Int
) {
    val sampleCount: Int get() = toSampleExclusive - fromSample

    /** 保留样本的总字节数（= 新 mdat 中该轨道占用的大小） */
    val byteSize: Long
        get() {
            var sum = 0L
            for (i in fromSample until toSampleExclusive) sum += track.sampleSizes[i]
            return sum
        }

    /** 保留片段的媒体时长（轨道 timescale） */
    val durationInTimescale: Long
        get() {
            if (sampleCount <= 0) return 0
            val last = toSampleExclusive - 1
            return track.sampleDts[last] + track.sampleDeltas[last] - track.sampleDts[fromSample]
        }
}

/** 剪辑计划：各轨道保留区间 + 需要从源文件下载的字节范围 */
class ClipPlan internal constructor(
    val tracks: List<TrackClip>,
    val rangeStart: Long,
    val rangeEndExclusive: Long
) {
    val rangeSize: Long get() = rangeEndExclusive - rangeStart
}

internal object Mp4Clipper {

    /**
     * 规划 [startMs, endMs) 的剪辑。起点吸附到关键帧，终点取 DTS <= endMs 的最后一个样本。
     * 所有轨道统一以参考轨道（视频优先）的时间窗为准。
     */
    fun planMs(parsed: ParsedMp4, startMs: Long, endMs: Long): ClipPlan {
        val ref = parsed.videoTrack
            ?: parsed.tracks.firstOrNull { it.isAudio }
            ?: throw Mp4ClipException("文件中没有可剪辑的音视频轨道")

        val refStart = ref.dtsOfMs(startMs.coerceAtLeast(0))
        val refEnd = ref.dtsOfMs(endMs)
        if (refEnd <= refStart) throw Mp4ClipException("所选时间段无效")

        // 视频起点：目标时间之前最近的关键帧；一个都找不到时从 0 开始保证可解码
        val refFrom = ref.lastSyncAtOrBefore(refStart).let { if (it < 0) 0 else it }
        // 参考轨道终点：DTS <= refEnd 的最后一个样本
        val refToIncl = ref.lastSampleAtOrBefore(refEnd)
        if (refToIncl < refFrom || refToIncl < 0) throw Mp4ClipException("所选时间段内没有媒体数据")

        val windowStartMs = ref.msOfDts(ref.sampleDts[refFrom])
        val windowEndMs = ref.msOfDts(ref.sampleDts[refToIncl] + ref.sampleDeltas[refToIncl])

        val clips = mutableListOf<TrackClip>()
        for (track in parsed.tracks) {
            if (!(track.isVideo || track.isAudio)) continue // 字幕/元数据等轨道直接丢弃
            val clip = if (track === ref) {
                TrackClip(track, refFrom, refToIncl + 1)
            } else {
                val from = track.firstSampleAtOrAfter(track.dtsOfMs(windowStartMs))
                val to = track.lastSampleAtOrBefore(track.dtsOfMs(windowEndMs))
                if (to < from || from >= track.sampleCount) continue // 该轨道在此窗口无数据
                TrackClip(track, from, to + 1)
            }
            if (clip.sampleCount > 0) clips.add(clip)
        }
        if (clips.isEmpty()) throw Mp4ClipException("所选时间段内没有媒体数据")

        var rangeStart = Long.MAX_VALUE
        var rangeEnd = Long.MIN_VALUE
        for (c in clips) {
            rangeStart = minOf(rangeStart, c.track.sampleOffsets[c.fromSample])
            val last = c.toSampleExclusive - 1
            rangeEnd = maxOf(rangeEnd, c.track.sampleOffsets[last] + c.track.sampleSizes[last])
        }
        if (rangeStart < 0 || rangeEnd <= rangeStart) throw Mp4ClipException("采样表异常")
        return ClipPlan(clips, rangeStart, rangeEnd)
    }

    /** 关键帧吸附后的实际起止时间（毫秒），用于 UI 提示“将剪出哪一段” */
    fun snappedWindowMs(parsed: ParsedMp4, startMs: Long, endMs: Long): Pair<Long, Long> {
        val ref = parsed.videoTrack
            ?: parsed.tracks.firstOrNull { it.isAudio }
            ?: return startMs to endMs
        val refFrom = ref.lastSyncAtOrBefore(ref.dtsOfMs(startMs.coerceAtLeast(0)))
            .let { if (it < 0) 0 else it }
        val refTo = ref.lastSampleAtOrBefore(ref.dtsOfMs(endMs))
            .let { if (it < 0) ref.sampleCount - 1 else it }
        if (refTo < refFrom) return startMs to endMs
        val s = ref.msOfDts(ref.sampleDts[refFrom])
        val e = ref.msOfDts(ref.sampleDts[refTo] + ref.sampleDeltas[refTo])
        return s to e
    }

    /**
     * 用已下载的片段重建 MP4 并写入 output。
     *
     * @param copySegment 把源文件 [absoluteOffset, absoluteOffset+length) 的字节写入 out；
     *                    实现方负责取消检查与 IO。
     * @param onProgress 已写入新文件的字节数回调（用于进度展示）
     */
    fun writeClip(
        output: OutputStream,
        parsed: ParsedMp4,
        plan: ClipPlan,
        copySegment: (absoluteOffset: Long, length: Long, out: OutputStream) -> Unit,
        onProgress: (bytesWritten: Long) -> Unit = {}
    ) {
        val ftyp = if (parsed.ftypBox.isNotEmpty()) parsed.ftypBox else buildDefaultFtyp()

        // mdat 头大小（是否需要 64 位 largesize）取决于负载大小，可先算出
        val payload = plan.tracks.sumOf { it.byteSize }
        val mdatHeader = if (payload + 8 > 0xFFFFFFFFL) 16 else 8

        // moov 大小与 chunk 偏移相互依赖：先用估计值构建量取大小（偏移值不影响 box 大小），
        // 再用真实偏移重建一次；若偏移超出 u32 则切换 co64（moov 每轨道变大 8 字节）再量一次
        var co64 = false
        var moovSize = buildMoov(parsed, plan, 0, false).size
        if (plan.tracks.any { t ->
                t.track.sampleOffsets[t.fromSample] - plan.rangeStart + ftyp.size + moovSize + mdatHeader >
                    0xFFFFFFFFL
            }
        ) {
            co64 = true
            moovSize = buildMoov(parsed, plan, 0, true).size
        }
        val mdatDataStart = (ftyp.size + moovSize + mdatHeader).toLong()
        val moov = buildMoov(parsed, plan, mdatDataStart, co64)

        output.write(ftyp)
        output.write(moov)
        writeMdatHeader(output, payload, mdatHeader)

        var written = 0L
        // 每个轨道一个 chunk；样本在源文件里连续的归并成段拷贝，减少小块 IO
        for (clip in plan.tracks) {
            val t = clip.track
            var i = clip.fromSample
            while (i < clip.toSampleExclusive) {
                var runEnd = i
                var runBytes = t.sampleSizes[i]
                while (runEnd + 1 < clip.toSampleExclusive &&
                    t.sampleOffsets[runEnd + 1] == t.sampleOffsets[runEnd] + t.sampleSizes[runEnd]
                ) {
                    runEnd++
                    runBytes += t.sampleSizes[runEnd]
                }
                copySegment(t.sampleOffsets[i], runBytes, output)
                written += runBytes
                onProgress(written)
                i = runEnd + 1
            }
        }
        if (written != payload) throw Mp4ClipException("写出字节数与计划不符")
    }

    private fun writeMdatHeader(output: OutputStream, payload: Long, header: Int) {
        val bb = java.nio.ByteBuffer.allocate(header).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (header == 8) {
            bb.putInt((payload + 8).toInt())
            bb.put("mdat".toByteArray(Charsets.US_ASCII))
        } else {
            bb.putInt(1)
            bb.put("mdat".toByteArray(Charsets.US_ASCII))
            bb.putLong(payload + 16)
        }
        output.write(bb.array())
    }

    private fun buildDefaultFtyp(): ByteArray {
        val b = BoxBuilder()
        b.box("ftyp") {
            b.ascii("isom")
            b.u32(512)
            b.ascii("isom")
            b.ascii("iso2")
            b.ascii("avc1")
            b.ascii("mp41")
        }
        return b.toByteArray()
    }

    /** 构建 moov。mdatDataStart 为 mdat 负载起始的绝对文件偏移，用于重定位 chunk 偏移。 */
    private fun buildMoov(parsed: ParsedMp4, plan: ClipPlan, mdatDataStart: Long, useCo64: Boolean): ByteArray {
        val b = BoxBuilder()
        val movieTs = if (parsed.movieTimescale > 0) parsed.movieTimescale else 1000

        // 各轨道媒体时长 -> 电影时长（movie timescale）
        val trackDurations = plan.tracks.map { clip ->
            val t = clip.track
            if (t.timescale <= 0) 0L else clip.durationInTimescale * movieTs / t.timescale
        }
        val movieDuration = trackDurations.maxOrNull() ?: 0L

        // 每个轨道在新 mdat 中的起始偏移
        val trackDataStart = ArrayList<Long>(plan.tracks.size)
        var pos = mdatDataStart
        for (clip in plan.tracks) {
            trackDataStart.add(pos)
            pos += clip.byteSize
        }

        b.box("moov") {
            val mvhd = parsed.mvhdBox
            val mvhdV1 = (mvhd[8].toInt() and 0xFF) == 1
            b.raw(patchDuration(mvhd, if (mvhdV1) 32 else 24, movieDuration, mvhdV1))

            plan.tracks.forEachIndexed { index, clip ->
                val t = clip.track
                b.box("trak") {
                    val tkhd = t.tkhdBox
                    val tkV1 = (tkhd[8].toInt() and 0xFF) == 1
                    b.raw(patchDuration(tkhd, if (tkV1) 36 else 28, trackDurations[index], tkV1))
                    b.box("mdia") {
                        val mdhd = t.mdhdBox
                        val mdV1 = (mdhd[8].toInt() and 0xFF) == 1
                        b.raw(patchDuration(mdhd, if (mdV1) 32 else 24, clip.durationInTimescale, mdV1))
                        if (t.hdlrBox.isNotEmpty()) b.raw(t.hdlrBox)
                        b.box("minf") {
                            t.mediaHeaderBox?.let { b.raw(it) }
                            t.dinfBox?.let { b.raw(it) }
                            b.box("stbl") {
                                b.raw(t.stsdBox)
                                buildStts(b, clip)
                                t.sampleCompositionOffsets?.let { buildCtts(b, clip, it) }
                                if (t.syncSamples != null) buildStss(b, clip, t)
                                // 新布局：整轨一个 chunk
                                b.fullBox("stsc", 0, 0) {
                                    b.u32(1)
                                    b.u32(1)
                                    b.u32(clip.sampleCount.toLong())
                                    b.u32(t.sampleDescIndexes[clip.fromSample].toLong())
                                }
                                buildStsz(b, clip)
                                val chunkOffset = trackDataStart[index]
                                if (useCo64) {
                                    b.fullBox("co64", 0, 0) { b.u32(1); b.u64(chunkOffset) }
                                } else {
                                    b.fullBox("stco", 0, 0) { b.u32(1); b.u32(chunkOffset) }
                                }
                            }
                        }
                    }
                }
            }
        }
        return b.toByteArray()
    }

    private fun buildStts(b: BoxBuilder, clip: TrackClip) {
        val t = clip.track
        // 对保留区间的增量做游程编码
        val runs = ArrayList<LongArray>()
        var i = clip.fromSample
        while (i < clip.toSampleExclusive) {
            val delta = t.sampleDeltas[i]
            var n = 1
            while (i + n < clip.toSampleExclusive && t.sampleDeltas[i + n] == delta) n++
            runs.add(longArrayOf(n.toLong(), delta))
            i += n
        }
        b.fullBox("stts", 0, 0) {
            b.u32(runs.size.toLong())
            for (r in runs) { b.u32(r[0]); b.u32(r[1]) }
        }
    }

    private fun buildCtts(b: BoxBuilder, clip: TrackClip, perSample: IntArray) {
        val t = clip.track
        val runs = ArrayList<LongArray>()
        var i = clip.fromSample
        while (i < clip.toSampleExclusive) {
            val off = perSample[i]
            var n = 1
            while (i + n < clip.toSampleExclusive && perSample[i + n] == off) n++
            runs.add(longArrayOf(n.toLong(), off.toLong()))
            i += n
        }
        val hasNegative = runs.any { it[1] < 0 }
        b.fullBox("ctts", if (hasNegative) 1 else 0, 0) {
            b.u32(runs.size.toLong())
            for (r in runs) { b.u32(r[0]); b.i32(r[1].toInt()) }
        }
    }

    private fun buildStss(b: BoxBuilder, clip: TrackClip, t: Mp4Track) {
        val sync = ArrayList<Int>()
        for (s in t.syncSamples!!) {
            val idx = s - 1
            if (idx in clip.fromSample until clip.toSampleExclusive) sync.add(idx - clip.fromSample + 1)
        }
        if (sync.isEmpty() || sync.size == clip.sampleCount) return // 全是关键帧时省略
        b.fullBox("stss", 0, 0) {
            b.u32(sync.size.toLong())
            for (v in sync) b.u32(v.toLong())
        }
    }

    private fun buildStsz(b: BoxBuilder, clip: TrackClip) {
        val t = clip.track
        val first = t.sampleSizes[clip.fromSample]
        var fixed = true
        for (i in clip.fromSample until clip.toSampleExclusive) {
            if (t.sampleSizes[i] != first) { fixed = false; break }
        }
        b.fullBox("stsz", 0, 0) {
            b.u32(if (fixed) first else 0)
            b.u32(clip.sampleCount.toLong())
            if (!fixed) {
                for (i in clip.fromSample until clip.toSampleExclusive) b.u32(t.sampleSizes[i])
            }
        }
    }

    /** 就地修补 box 内的 duration 字段（v0 为 u32，v1 为 u64），大端序 */
    private fun patchDuration(box: ByteArray, offset: Int, value: Long, is64: Boolean): ByteArray {
        val copy = box.clone()
        if (is64) {
            for (i in 7 downTo 0) copy[offset + (7 - i)] = (value ushr (i * 8)).toByte()
        } else {
            if (value > 0xFFFFFFFFL) throw Mp4ClipException("时长超出 u32 且 box 版本不支持")
            for (i in 3 downTo 0) copy[offset + (3 - i)] = (value ushr (i * 8)).toByte()
        }
        return copy
    }
}

/**
 * 极简 box 写入器：自持缓冲，box() 关闭时在原地回填大小，整体 O(n)。
 */
internal class BoxBuilder {
    private var buf = ByteArray(4096)
    private var len = 0

    fun toByteArray(): ByteArray = buf.copyOf(len)
    fun size(): Int = len

    fun box(type: String, body: () -> Unit) {
        ensureCapacity(8)
        val sizePos = len
        writeInt32(0)
        writeAscii(type)
        body()
        val size = len - sizePos
        buf[sizePos] = (size ushr 24).toByte()
        buf[sizePos + 1] = (size ushr 16).toByte()
        buf[sizePos + 2] = (size ushr 8).toByte()
        buf[sizePos + 3] = size.toByte()
    }

    fun fullBox(type: String, version: Int, flags: Int, body: () -> Unit) {
        box(type) {
            u8(version)
            u24(flags)
            body()
        }
    }

    fun raw(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        System.arraycopy(bytes, 0, buf, len, bytes.size)
        len += bytes.size
    }

    fun u8(v: Int) {
        ensureCapacity(1)
        buf[len++] = v.toByte()
    }

    fun u16(v: Int) {
        ensureCapacity(2)
        buf[len++] = (v ushr 8).toByte()
        buf[len++] = v.toByte()
    }

    fun u24(v: Int) {
        ensureCapacity(3)
        buf[len++] = (v ushr 16).toByte()
        buf[len++] = (v ushr 8).toByte()
        buf[len++] = v.toByte()
    }

    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u32(v: Long) {
        ensureCapacity(4)
        buf[len++] = (v ushr 24).toByte()
        buf[len++] = (v ushr 16).toByte()
        buf[len++] = (v ushr 8).toByte()
        buf[len++] = v.toByte()
    }

    fun u64(v: Long) {
        ensureCapacity(8)
        for (i in 7 downTo 0) buf[len++] = (v ushr (i * 8)).toByte()
    }

    fun ascii(s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        require(bytes.size == s.length) { "box 类型只能用 ASCII" }
        raw(bytes)
    }

    private fun writeInt32(v: Int) {
        buf[len++] = (v ushr 24).toByte()
        buf[len++] = (v ushr 16).toByte()
        buf[len++] = (v ushr 8).toByte()
        buf[len++] = v.toByte()
    }

    private fun writeAscii(s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, len, bytes.size)
        len += bytes.size
    }

    private fun ensureCapacity(extra: Int) {
        if (len + extra <= buf.size) return
        var newSize = buf.size
        while (newSize < len + extra) newSize = newSize * 2
        buf = buf.copyOf(newSize)
    }
}
