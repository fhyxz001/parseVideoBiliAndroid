package com.videoparser.bili

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 MP4(ISO-BMFF) 元数据解析，只服务剪辑功能。
 *
 * 剪辑需要回答两个问题：
 * 1. 视频总时长是多少（mvhd/mdhd）；
 * 2. 任意时间段对应文件里的哪些字节（stbl 采样表）。
 *
 * 因此这里只解析容器级的 box，不触碰编解码数据。纯 JVM 实现，可直接单元测试。
 */

/** 解析失败（结构损坏、缺关键 box、分片 MP4 等不支持的情况） */
class Mp4ParseException(message: String) : Exception(message)

/** 单个轨道的采样索引：每个样本的大小 / 文件偏移 / 解码时间 */
class Mp4Track internal constructor(
    val trackId: Int,
    val handlerType: String,
    val timescale: Long,
    internal val tkhdBox: ByteArray,
    internal val mdhdBox: ByteArray,
    internal val hdlrBox: ByteArray,
    internal val mediaHeaderBox: ByteArray?,   // vmhd / smhd
    internal val dinfBox: ByteArray?,
    internal val stsdBox: ByteArray,
    val sampleSizes: LongArray,
    val sampleOffsets: LongArray,
    val sampleDeltas: LongArray,
    val sampleDts: LongArray,
    val sampleCompositionOffsets: IntArray?,   // ctts，无 B 帧时为 null
    /** 1-based 同步样本（关键帧）编号；null 表示所有样本都是同步样本 */
    val syncSamples: IntArray?,
    /** 每个样本的采样描述索引（stsd 条目编号，0-based 起算为 1） */
    val sampleDescIndexes: IntArray
) {
    val isVideo: Boolean get() = handlerType == "vide"
    val isAudio: Boolean get() = handlerType == "soun"
    val sampleCount: Int get() = sampleSizes.size

    /** 轨道媒体总时长（timescale 单位）= 最后一个样本的 DTS + 增量 */
    val durationInTimescale: Long
        get() = if (sampleCount == 0) 0 else sampleDts[sampleCount - 1] + sampleDeltas[sampleCount - 1]

    fun dtsOfMs(ms: Long): Long = timescale * ms / 1000
    fun msOfDts(dts: Long): Long = if (timescale > 0) dts * 1000 / timescale else 0

    /** 最后一个 DTS <= target 的样本下标；没有则返回 -1 */
    fun lastSampleAtOrBefore(target: Long): Int {
        var lo = 0
        var hi = sampleCount - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sampleDts[mid] <= target) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** 第一个 DTS >= target 的样本下标；没有则返回 sampleCount */
    fun firstSampleAtOrAfter(target: Long): Int {
        var lo = 0
        var hi = sampleCount - 1
        var ans = sampleCount
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sampleDts[mid] >= target) { ans = mid; hi = mid - 1 } else lo = mid + 1
        }
        return ans
    }

    /** 目标时间之前最近的关键帧样本下标（含 target 本身是关键帧的情况）；找不到返回 -1 */
    fun lastSyncAtOrBefore(target: Long): Int {
        if (syncSamples == null) return lastSampleAtOrBefore(target)
        var lo = 0
        var hi = syncSamples.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val idx = syncSamples[mid] - 1
            if (idx in 0 until sampleCount && sampleDts[idx] <= target) { ans = idx; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** moov 解析结果 */
class ParsedMp4 internal constructor(
    internal val ftypBox: ByteArray,
    internal val mvhdBox: ByteArray,
    val movieTimescale: Long,
    val tracks: List<Mp4Track>
) {
    val hasVideoTrack: Boolean get() = tracks.any { it.isVideo }
    val videoTrack: Mp4Track? get() = tracks.firstOrNull { it.isVideo }

    /** 按 mvhd 计算的总时长（毫秒） */
    val durationMs: Long
        get() = if (movieTimescale <= 0) 0 else tracks.maxOf { t ->
            t.durationInTimescale * movieTimescale / t.timescale
        } * 1000 / movieTimescale
}

internal object Mp4BoxIo {

    /** 遍历 [start, end) 内的 box 序列。block 返回 false 提前终止。
     *  回调参数：type、boxStart、boxEndExclusive、bodyStart。遇到损坏结构立即停止。 */
    inline fun forEachBox(
        buf: ByteArray,
        start: Int,
        end: Int,
        block: (type: String, boxStart: Int, boxEnd: Int, bodyStart: Int) -> Boolean
    ) {
        var p = start
        while (p + 8 <= end) {
            var size = readU32(buf, p)
            val type = readType(buf, p + 4)
            var header = 8
            if (size == 1L) {
                if (p + 16 > end) return
                size = readU64(buf, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < header) return
            val boxEnd = p.toLong() + size
            if (boxEnd > end) return
            var bodyStart = p + header
            if (type == "uuid") bodyStart += 16
            if (!block(type, p, boxEnd.toInt(), bodyStart)) return
            p = boxEnd.toInt()
        }
    }

    /** 在 [start, end) 中查找指定类型的子 box，返回 boxStart，找不到返回 -1 */
    fun findBox(buf: ByteArray, start: Int, end: Int, type: String): Int {
        var found = -1
        forEachBox(buf, start, end) { t, s, _, _ ->
            if (t == type) { found = s; false } else true
        }
        return found
    }

    /** box 自身 header 之后的位置（即其子 box 序列的起始位置） */
    fun bodyOffset(buf: ByteArray, boxStart: Int): Int =
        if (readU32(buf, boxStart) == 1L) boxStart + 16 else boxStart + 8

    fun readU32(buf: ByteArray, pos: Int): Long =
        ((buf[pos].toLong() and 0xFF) shl 24) or ((buf[pos + 1].toLong() and 0xFF) shl 16) or
            ((buf[pos + 2].toLong() and 0xFF) shl 8) or (buf[pos + 3].toLong() and 0xFF)

    fun readU64(buf: ByteArray, pos: Int): Long =
        ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.BIG_ENDIAN).long

    fun readI32(buf: ByteArray, pos: Int): Int = readU32(buf, pos).toInt()

    fun readType(buf: ByteArray, pos: Int): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val c = buf[pos + i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '?')
        }
        return sb.toString()
    }

    fun copy(buf: ByteArray, from: Int, to: Int): ByteArray {
        val n = to - from
        if (n <= 0) return ByteArray(0)
        return buf.copyOfRange(from, to)
    }
}

internal object Mp4Parser {

    /** 解析 moov（ftyp 可为空数组，仅用于原样复制到剪辑产物） */
    fun parse(ftypBox: ByteArray, moovBox: ByteArray): ParsedMp4 {
        if (moovBox.size < 8) throw Mp4ParseException("moov 数据为空")

        var movieTimescale = 0L
        var mvhdBox: ByteArray? = null
        val tracks = mutableListOf<Mp4Track>()

        // 传入的 moovBox 自带 8 字节 box 头，子 box 从其 body 开始
        val moovBody = Mp4BoxIo.bodyOffset(moovBox, 0)
        Mp4BoxIo.forEachBox(moovBox, moovBody, moovBox.size) { type, s, e, body ->
            when (type) {
                "mvhd" -> {
                    mvhdBox = Mp4BoxIo.copy(moovBox, s, e)
                    movieTimescale = readMvhdTimescale(mvhdBox!!)
                }
                "trak" -> parseTrak(moovBox, s, e)?.let { tracks.add(it) }
            }
            true
        }

        if (mvhdBox == null) throw Mp4ParseException("缺少 mvhd")
        if (tracks.isEmpty()) throw Mp4ParseException("moov 中没有轨道")
        return ParsedMp4(ftypBox, mvhdBox!!, movieTimescale, tracks)
    }

    private fun readMvhdTimescale(box: ByteArray): Long {
        val version = box[8].toInt() and 0xFF
        val body = 8 + 4 // header + version/flags
        return if (version == 1) Mp4BoxIo.readU32(box, body + 16)
        else Mp4BoxIo.readU32(box, body + 8)
    }

    private fun parseTrak(buf: ByteArray, trakStart: Int, trakEnd: Int): Mp4Track? {
        var trackId = 0
        var timescale = 0L
        var handler = ""
        var tkhd: ByteArray? = null
        var mdhd: ByteArray? = null
        var hdlr: ByteArray? = null
        var mediaHeader: ByteArray? = null
        var dinf: ByteArray? = null
        var stsd: ByteArray? = null

        var sttsEntries: LongArray? = null
        var stss: IntArray? = null
        var stscChunks: IntArray? = null
        var stscPerChunk: IntArray? = null
        var stscDesc: IntArray? = null
        var stszFixed = 0L
        var stszCount = 0
        var stszTable: LongArray? = null
        var chunkOffsets: LongArray? = null
        var cttsVersion = 0
        var cttsCounts: IntArray? = null
        var cttsOffsets: IntArray? = null

        // trak 自带头部，子 box 从 body 开始
        Mp4BoxIo.forEachBox(buf, Mp4BoxIo.bodyOffset(buf, trakStart), trakEnd) { type, s, e, body ->
            when (type) {
                "tkhd" -> tkhd = Mp4BoxIo.copy(buf, s, e)
                "edts" -> return@forEachBox true // 丢弃编辑列表，剪辑后从媒体时间 0 开始
                "mdia" -> Mp4BoxIo.forEachBox(buf, body, e) { t2, s2, e2, body2 ->
                    when (t2) {
                        "mdhd" -> {
                            mdhd = Mp4BoxIo.copy(buf, s2, e2)
                            timescale = readMdhdTimescale(mdhd!!)
                        }
                        "hdlr" -> {
                            hdlr = Mp4BoxIo.copy(buf, s2, e2)
                            handler = Mp4BoxIo.readType(buf, body2 + 8)
                        }
                        "minf" -> Mp4BoxIo.forEachBox(buf, body2, e2) { t3, s3, e3, body3 ->
                            when (t3) {
                                "vmhd", "smhd", "hmhd", "nmhd", "gmhd" ->
                                    if (mediaHeader == null) mediaHeader = Mp4BoxIo.copy(buf, s3, e3)
                                "dinf" -> if (dinf == null) dinf = Mp4BoxIo.copy(buf, s3, e3)
                                "stbl" -> Mp4BoxIo.forEachBox(buf, body3, e3) { t4, s4, e4, body4 ->
                                    when (t4) {
                                        "stsd" -> stsd = Mp4BoxIo.copy(buf, s4, e4)
                                        "stts" -> sttsEntries = parseRunLengthPairs(buf, body4, e4)
                                        "stss" -> stss = parseIntList(buf, body4, e4)
                                        "stsc" -> {
                                            val triples = parseRunLengthTriples(buf, body4, e4)
                                            stscChunks = triples.first
                                            stscPerChunk = triples.second
                                            stscDesc = triples.third
                                        }
                                        "stsz" -> {
                                            val parsed = parseStsz(buf, body4, e4)
                                            stszFixed = parsed.first
                                            stszCount = parsed.second
                                            stszTable = parsed.third
                                        }
                                        "stco" -> chunkOffsets = parseU32List(buf, body4, e4)
                                        "co64" -> chunkOffsets = parseU64List(buf, body4, e4)
                                        "ctts" -> {
                                            val parsed = parseCtts(buf, body4, e4)
                                            cttsVersion = parsed.first
                                            cttsCounts = parsed.second
                                            cttsOffsets = parsed.third
                                        }
                                    }
                                    true
                                }
                            }
                            true
                        }
                    }
                    true
                }
            }
            true
        }

        val stsdBox = stsd ?: return null
        val mdhdBox = mdhd ?: return null
        val stts = sttsEntries ?: return null
        val offsets = chunkOffsets ?: return null
        if (timescale <= 0) return null
        if (trackId == 0) {
            // tkhd 的 track_ID：v0 在 body+12，v1 在 body+20
            val tk = tkhd
            if (tk != null && tk.size > 28) {
                val version = tk[8].toInt() and 0xFF
                trackId = Mp4BoxIo.readU32(tk, 8 + 4 + (if (version == 1) 16 else 8)).toInt()
            }
        }

        // stsz -> 每样本大小
        val sampleCount = if (stszFixed > 0) stszCount else (stszTable?.size ?: 0)
        if (sampleCount <= 0) return null
        val sizes = LongArray(sampleCount)
        if (stszFixed > 0) {
            java.util.Arrays.fill(sizes, stszFixed)
        } else {
            stszTable!!.copyInto(sizes)
        }

        // stts -> 每样本增量（不足时用最后一个增量补齐）
        val deltas = LongArray(sampleCount)
        var p = 0
        var lastDelta = 0L
        var i = 0
        while (i + 1 < stts.size && p < sampleCount) {
            val count = stts[i]
            val delta = stts[i + 1]
            lastDelta = delta
            var c = 0L
            while (c < count && p < sampleCount) {
                deltas[p++] = delta
                c++
            }
            i += 2
        }
        while (p < sampleCount) deltas[p++] = lastDelta

        // 累积 DTS
        val dts = LongArray(sampleCount)
        for (k in 1 until sampleCount) dts[k] = dts[k - 1] + deltas[k - 1]

        // stsc -> 每个 chunk 的样本数与描述索引
        val chunkCount = offsets.size
        val perChunk = IntArray(chunkCount)
        val descIdx = IntArray(chunkCount)
        val c1 = stscChunks
        val c2 = stscPerChunk
        val c3 = stscDesc
        if (c1 != null && c2 != null && c3 != null && c1.isNotEmpty()) {
            var ei = 0
            for (c in 0 until chunkCount) {
                while (ei + 1 < c1.size && c1[ei + 1] - 1 <= c) ei++
                perChunk[c] = c2[ei].coerceAtLeast(0)
                descIdx[c] = c3[ei]
            }
        } else {
            java.util.Arrays.fill(perChunk, 1)
        }

        // 按顺序展开每个样本的文件偏移（并记录样本所属 chunk 的描述索引）
        val sampleOffsets = LongArray(sampleCount)
        val sampleDesc = IntArray(sampleCount)
        var si = 0
        var ok = true
        for (c in 0 until chunkCount) {
            var pos = offsets[c]
            if (pos <= 0) { ok = false; break }
            var n = 0
            while (n < perChunk[c] && si < sampleCount) {
                sampleOffsets[si] = pos
                sampleDesc[si] = descIdx[c]
                pos += sizes[si]
                si++
                n++
            }
            if (pos > Long.MAX_VALUE / 2) { ok = false; break }
        }
        if (!ok || si != sampleCount) throw Mp4ParseException("采样表不一致（stsc/stco/stsz）")

        // ctts -> 每样本合成偏移
        var cttsPerSample: IntArray? = null
        if (cttsCounts != null && cttsOffsets != null) {
            val arr = IntArray(sampleCount)
            var q = 0
            var j = 0
            while (j < cttsCounts!!.size && q < sampleCount) {
                var c = 0
                val off = cttsOffsets!![j]
                while (c < cttsCounts!![j] && q < sampleCount) {
                    arr[q++] = off
                    c++
                }
                j++
            }
            cttsPerSample = arr
        }

        // stss 同步样本必须升序，防御性排序
        val sync = stss?.let {
            val sorted = it.clone()
            java.util.Arrays.sort(sorted)
            sorted
        }

        return Mp4Track(
            trackId = trackId,
            handlerType = handler,
            timescale = timescale,
            tkhdBox = tkhd ?: return null,
            mdhdBox = mdhdBox,
            hdlrBox = hdlr ?: ByteArray(0),
            mediaHeaderBox = mediaHeader,
            dinfBox = dinf,
            stsdBox = stsdBox,
            sampleSizes = sizes,
            sampleOffsets = sampleOffsets,
            sampleDeltas = deltas,
            sampleDts = dts,
            sampleCompositionOffsets = cttsPerSample,
            syncSamples = sync,
            sampleDescIndexes = sampleDesc
        )
    }

    private fun readMdhdTimescale(box: ByteArray): Long {
        val version = box[8].toInt() and 0xFF
        val body = 8 + 4
        return if (version == 1) Mp4BoxIo.readU32(box, body + 16)
        else Mp4BoxIo.readU32(box, body + 8)
    }

    /** stts 的 (sampleCount, sampleDelta) 对；bodyStart 指向 version/flags */
    private fun parseRunLengthPairs(buf: ByteArray, bodyStart: Int, boxEnd: Int): LongArray {
        val entryCount = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val out = LongArray(entryCount * 2)
        var p = bodyStart + 8
        var o = 0
        for (i in 0 until entryCount) {
            if (p + 8 > boxEnd) break
            out[o++] = Mp4BoxIo.readU32(buf, p)
            out[o++] = Mp4BoxIo.readU32(buf, p + 4)
            p += 8
        }
        return out.copyOf(o)
    }

    private fun parseCtts(buf: ByteArray, bodyStart: Int, boxEnd: Int): Triple<Int, IntArray, IntArray> {
        val version = buf[bodyStart].toInt() and 0xFF
        val entryCount = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val counts = IntArray(entryCount)
        val offsets = IntArray(entryCount)
        var p = bodyStart + 8
        var n = 0
        for (i in 0 until entryCount) {
            if (p + 8 > boxEnd) break
            counts[n] = Mp4BoxIo.readU32(buf, p).toInt()
            offsets[n] = Mp4BoxIo.readI32(buf, p + 4)
            n++
            p += 8
        }
        return Triple(version, counts.copyOf(n), offsets.copyOf(n))
    }

    private fun parseRunLengthTriples(
        buf: ByteArray,
        bodyStart: Int,
        boxEnd: Int
    ): Triple<IntArray, IntArray, IntArray> {
        val entryCount = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val first = IntArray(entryCount)
        val per = IntArray(entryCount)
        val desc = IntArray(entryCount)
        var p = bodyStart + 8
        var n = 0
        for (i in 0 until entryCount) {
            if (p + 12 > boxEnd) break
            first[n] = Mp4BoxIo.readU32(buf, p).toInt()
            per[n] = Mp4BoxIo.readU32(buf, p + 4).toInt()
            desc[n] = Mp4BoxIo.readU32(buf, p + 8).toInt()
            n++
            p += 12
        }
        return Triple(first.copyOf(n), per.copyOf(n), desc.copyOf(n))
    }

    /** stsz：返回 (固定样本大小[0=变长]，样本数，变长表) */
    private fun parseStsz(buf: ByteArray, bodyStart: Int, boxEnd: Int): Triple<Long, Int, LongArray?> {
        val fixed = Mp4BoxIo.readU32(buf, bodyStart + 4)
        val count = Mp4BoxIo.readU32(buf, bodyStart + 8).toInt()
        if (fixed > 0) return Triple(fixed, count, null)
        val table = LongArray(count)
        var p = bodyStart + 12
        for (i in 0 until count) {
            if (p + 4 > boxEnd) break
            table[i] = Mp4BoxIo.readU32(buf, p)
            p += 4
        }
        return Triple(0L, count, table)
    }

    private fun parseIntList(buf: ByteArray, bodyStart: Int, boxEnd: Int): IntArray {
        val count = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val out = IntArray(count)
        var p = bodyStart + 8
        for (i in 0 until count) {
            if (p + 4 > boxEnd) break
            out[i] = Mp4BoxIo.readU32(buf, p).toInt()
            p += 4
        }
        return out
    }

    private fun parseU32List(buf: ByteArray, bodyStart: Int, boxEnd: Int): LongArray {
        val count = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val out = LongArray(count)
        var p = bodyStart + 8
        for (i in 0 until count) {
            if (p + 4 > boxEnd) break
            out[i] = Mp4BoxIo.readU32(buf, p)
            p += 4
        }
        return out
    }

    private fun parseU64List(buf: ByteArray, bodyStart: Int, boxEnd: Int): LongArray {
        val count = Mp4BoxIo.readU32(buf, bodyStart + 4).toInt()
        val out = LongArray(count)
        var p = bodyStart + 8
        for (i in 0 until count) {
            if (p + 8 > boxEnd) break
            out[i] = Mp4BoxIo.readU64(buf, p)
            p += 8
        }
        return out
    }
}

/**
 * 顶层 box 定位：判断 moov 在文件的头部（完整/被截断）还是尾部，
 * 剪辑下载据此决定取哪段字节。纯字节缓冲实现，便于单元测试。
 */
internal object Mp4FileLocator {

    class HeadScan(
        val ftypBox: ByteArray?,
        /** 头部缓冲中完整包含的 moov；非空时可直接解析 */
        val moovBox: ByteArray?,
        /** moov 起始的绝对文件偏移（完整或截断时均有效，否则 -1） */
        val moovStart: Long,
        /** moov 的 box 总大小（截断时用于精确补拉） */
        val moovSize: Long,
        /** 是否发现 moof（分片 MP4，走降级路径） */
        val hasMoof: Boolean
    )

    /**
     * 扫描文件头部缓冲。
     * 与 forEachBox 不同，这里需要感知“最后一个 box 被缓冲截断”的情况
     * （moov 比缓冲大时仍要报告其精确大小，供精确补拉），因此手工顺序扫描。
     * @param headOffset 缓冲第 0 字节对应的文件偏移（一般为 0）
     */
    fun scanHead(head: ByteArray, headOffset: Long = 0): HeadScan {
        var ftyp: ByteArray? = null
        var moovBox: ByteArray? = null
        var moovStart = -1L
        var moovSize = -1L
        var hasMoof = false

        var p = 0
        while (p + 8 <= head.size) {
            val type = Mp4BoxIo.readType(head, p + 4)
            var header = 8
            var size = Mp4BoxIo.readU32(head, p)
            if (size == 1L) {
                if (p + 16 > head.size) break
                size = Mp4BoxIo.readU64(head, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (head.size - p).toLong()
            }
            val boxEnd = p.toLong() + size
            when (type) {
                "ftyp" -> if (boxEnd <= head.size) ftyp = Mp4BoxIo.copy(head, p, boxEnd.toInt())
                "moov" -> {
                    moovStart = headOffset + p
                    moovSize = size
                    if (boxEnd <= head.size) moovBox = Mp4BoxIo.copy(head, p, boxEnd.toInt())
                    return HeadScan(ftyp, moovBox, moovStart, moovSize, hasMoof)
                }
                "moof" -> return HeadScan(ftyp, null, -1L, -1L, true)
            }
            if (size < header || boxEnd > head.size) break // 结构不完整或损坏，停止顺序解析
            p = boxEnd.toInt()
        }
        return HeadScan(ftyp, moovBox, moovStart, moovSize, hasMoof)
    }

    /**
     * 在尾部缓冲中搜索 moov 的起点（缓冲可能从 mdat 中间开始，无法顺序解析）。
     * 返回 moov 的（绝对起始偏移, box 大小），找不到返回 null。
     * @param tailStart 缓冲第 0 字节对应的文件偏移
     * @param fileSize  完整文件大小，用于校验 size 字段的合理性
     */
    fun locateMoovInTail(tail: ByteArray, tailStart: Long, fileSize: Long): Pair<Long, Long>? {
        var from = 4 // 候选 size 字段至少要落在缓冲内
        while (true) {
            val idx = indexOfFourCc(tail, from, "moov") ?: return null
            val candidate = idx - 4
            if (candidate >= 0) {
                val size = run {
                    val s = Mp4BoxIo.readU32(tail, candidate)
                    if (s == 1L && candidate + 16 <= tail.size) Mp4BoxIo.readU64(tail, candidate + 8) else s
                }
                val absStart = tailStart + candidate
                if (size >= 8 && absStart + size <= fileSize) {
                    return absStart to size
                }
            }
            from = idx + 1
        }
    }

    private fun indexOfFourCc(buf: ByteArray, from: Int, cc: String): Int? {
        val a = cc[0].code.toByte()
        val b = cc[1].code.toByte()
        val c = cc[2].code.toByte()
        val d = cc[3].code.toByte()
        var i = from.coerceAtLeast(0)
        val limit = buf.size - 4
        while (i <= limit) {
            if (buf[i] == a && buf[i + 1] == b && buf[i + 2] == c && buf[i + 3] == d) return i
            i++
        }
        return null
    }
}
