package com.videoparser.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/**
 * 剪辑核心的单元测试：
 * 1. 合成 MP4（双轨道、交错 chunk、关键帧表、ctts、变长/定长样本）验证解析/规划/重建的数学正确性；
 * 2. 用真实 ffmpeg 生成的 MP4 做端到端验证（ffprobe 校验 + 全量解码），本机无 ffmpeg 时自动跳过。
 */
class Mp4ClipTest {

    /* ══════════════ 合成 MP4 构建 ══════════════ */

    // 视频轨道：25fps（timescale 12800，delta 512），750 帧 = 15s，每 15 帧一个关键帧
    private val vCount = 750
    private val vDelta = 512L
    private val vTs = 12800L
    private val vSyncEvery = 15

    // 音频轨道：模拟 AAC（每样本 1024 采样），600 包 ≈ 13.9s，定长样本
    private val aCount = 600
    private val aDelta = 1024L
    private val aTs = 44100L
    private val aFixed = 350L

    private val vPerChunk = 25
    private val aPerChunk = 50

    private fun vSizeAt(i: Int): Long =
        if (i % vSyncEvery == 0) 5000L + (i % 7) * 111 else 900L + (i % 11) * 37

    private fun vPayloadSize(): Long {
        var sum = 0L
        for (i in 0 until vCount) sum += vSizeAt(i)
        return sum
    }

    private fun sync1Based(): IntArray {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < vCount) { list.add(i + 1); i += vSyncEvery }
        return list.toIntArray()
    }

    /** 结构合法的假 stsd（本测试只验证容器数学，不需要真实编码数据） */
    private fun fakeStsd(type: String): ByteArray {
        val b = BoxBuilder()
        b.fullBox("stsd", 0, 0) {
            b.u32(1)
            b.box(type) {
                repeat(7) { b.u32(0x0F0F0F0F) }
            }
        }
        return b.toByteArray()
    }

    /**
     * 构建结构完整的合成 MP4（ftyp + moov + mdat），chunk 布局为视频/音频交错，
     * mdat 用确定性伪随机数据填充，以便验证剪辑后样本字节逐一对得上。
     */
    private fun buildSyntheticMp4(): ByteArray {
        val vChunkN = (vCount + vPerChunk - 1) / vPerChunk      // = 30
        val aChunkN = (aCount + aPerChunk - 1) / aPerChunk      // = 12

        fun buildMoov(mdatDataStart: Long): ByteArray {
            // 交错布局：视频 chunk 与音频 chunk 轮流
            var pos = mdatDataStart
            val vOffsets = mutableListOf<Long>()
            val aOffsets = mutableListOf<Long>()
            var vi = 0
            var ai = 0
            var vk = 0
            var ak = 0
            while (vi < vCount || ai < aCount) {
                if (vi < vCount) {
                    vOffsets.add(pos)
                    val n = minOf(vPerChunk, vCount - vi)
                    for (i in vi until vi + n) pos += vSizeAt(i)
                    vi += n; vk++
                }
                if (ai < aCount) {
                    aOffsets.add(pos)
                    pos += minOf(aPerChunk, aCount - ai) * aFixed
                    ai += minOf(aPerChunk, aCount - ai); ak++
                }
            }
            check(vk == vChunkN && ak == aChunkN)

            // stsc：视频/音频 chunk 交替，各轨道的 chunk 序号独立编号
            val vStsc = (1..vChunkN).map { Triple(it, vPerChunk, 1) }
            val aStsc = (1..aChunkN).map { Triple(it, aPerChunk, 1) }

            val b = BoxBuilder()
            b.box("moov") {
                b.fullBox("mvhd", 0, 0) {
                    b.u32(0); b.u32(0)               // creation, modification
                    b.u32(1000)                       // timescale
                    b.u32(15000)                      // duration (ms)
                    b.u32(0x00010000); b.u16(0x0100)  // rate, volume
                    b.u16(0); b.u32(0)                // reserved
                    repeat(9) { b.u32(if (it == 0 || it == 4 || it == 8) 0x00010000 else 0) } // matrix
                    repeat(6) { b.u32(0) }            // pre_defined
                    b.u32(3)                          // next_track_ID
                }
                fun trak(
                    trackId: Int, ts: Long, duration: Long, handler: String,
                    stsd: ByteArray, sync: IntArray?, fixed: Long?, sizes: LongArray?,
                    stsc: List<Triple<Int, Int, Int>>, chunkOffs: List<Long>
                ) {
                    b.box("trak") {
                        b.fullBox("tkhd", 0, 0) {
                            b.u32(0); b.u32(0); b.u32(trackId.toLong()); b.u32(0)
                            b.u32(duration * 1000 / ts)
                            b.u32(0); b.u32(0)         // reserved
                            b.u16(0); b.u16(0)         // layer, alternate_group
                            b.u16(0x0100); b.u16(0)    // volume, reserved
                            repeat(9) { b.u32(if (it == 0 || it == 4 || it == 8) 0x00010000 else 0) }
                            b.u32(0); b.u32(0)         // width, height
                        }
                        b.box("mdia") {
                            b.fullBox("mdhd", 0, 0) {
                                b.u32(0); b.u32(0); b.u32(ts); b.u32(duration); b.u16(0x55C4); b.u16(0)
                            }
                            b.fullBox("hdlr", 0, 0) {
                                b.u32(0); b.ascii(handler)
                                b.u32(0); b.u32(0); b.u32(0); b.ascii("dumy")
                            }
                            b.box("minf") {
                                if (handler == "vide") b.fullBox("vmhd", 0, 1) { b.u16(0); b.u16(0); b.u16(0); b.u16(0) }
                                else b.fullBox("smhd", 0, 0) { b.u16(0); b.u16(0) }
                                b.box("dinf") { b.fullBox("dref", 0, 0) { b.u32(1); b.fullBox("url ", 0, 1) {} } }
                                b.box("stbl") {
                                    b.raw(stsd)
                                    b.fullBox("stts", 0, 0) {
                                        b.u32(1)
                                        b.u32(if (handler == "vide") vCount.toLong() else aCount.toLong())
                                        b.u32(if (handler == "vide") vDelta else aDelta)
                                    }
                                    sync?.let {
                                        b.fullBox("stss", 0, 0) { b.u32(it.size.toLong()); it.forEach { s -> b.u32(s.toLong()) } }
                                    }
                                    if (handler == "vide") {
                                        // 0/512 交替的合成偏移，验证 ctts 裁剪
                                        b.fullBox("ctts", 0, 0) {
                                            b.u32(2)
                                            b.u32(375); b.i32(0)
                                            b.u32(375); b.i32(512)
                                        }
                                    }
                                    b.fullBox("stsc", 0, 0) {
                                        b.u32(stsc.size.toLong())
                                        stsc.forEach { b.u32(it.first.toLong()); b.u32(it.second.toLong()); b.u32(it.third.toLong()) }
                                    }
                                    b.fullBox("stsz", 0, 0) {
                                        if (fixed != null) {
                                            b.u32(fixed)
                                            b.u32(if (handler == "vide") vCount.toLong() else aCount.toLong())
                                        } else {
                                            b.u32(0); b.u32(sizes!!.size.toLong()); sizes!!.forEach { b.u32(it) }
                                        }
                                    }
                                    b.fullBox("stco", 0, 0) { b.u32(chunkOffs.size.toLong()); chunkOffs.forEach { b.u32(it) } }
                                }
                            }
                        }
                    }
                }
                trak(1, vTs, vCount * vDelta, "vide", fakeStsd("avc1"), sync1Based(), null, LongArray(vCount) { vSizeAt(it) }, vStsc, vOffsets)
                trak(2, aTs, aCount * aDelta, "soun", fakeStsd("mp4a"), null, aFixed, null, aStsc, aOffsets)
            }
            return b.toByteArray()
        }

        val ftyp = BoxBuilder().also { b ->
            b.box("ftyp") { b.ascii("isom"); b.u32(512); b.ascii("isom"); b.ascii("iso2"); b.ascii("avc1"); b.ascii("mp41") }
        }.toByteArray()

        val moovSize = buildMoov(0).size
        val mdatDataStart = (ftyp.size + moovSize + 8).toLong()
        val moov = buildMoov(mdatDataStart)
        check(moov.size == moovSize) { "moov 大小应与占位构建一致" }

        val payloadSize = vPayloadSize() + aFixed * aCount
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(moov)
        val bb = java.nio.ByteBuffer.allocate(8)
        bb.putInt(payloadSize.toInt() + 8)
        bb.put("mdat".toByteArray(Charsets.US_ASCII))
        out.write(bb.array())
        var seed = 12345L
        val buf = ByteArray(64 * 1024)
        var remaining = payloadSize
        while (remaining > 0) {
            val n = minOf(buf.size.toLong(), remaining).toInt()
            for (i in 0 until n) { seed = seed * 6364136223846793005L + 1442695040888963407L; buf[i] = (seed ushr 33).toByte() }
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /**
     * 模拟“Range 下载后的片段文件”：segment 即下载到的 [rangeStart, rangeEnd) 字节，
     * copySegment 收到绝对偏移时在其中按 (offset - rangeStart) 定位——与生产逻辑一致。
     */
    private fun segmentCopy(segment: ByteArray, rangeStart: Long): (Long, Long, OutputStream) -> Unit =
        { offset, length, out -> out.write(segment, (offset - rangeStart).toInt(), length.toInt()) }

    private fun extractSegment(source: ByteArray, plan: ClipPlan): ByteArray =
        source.copyOfRange(plan.rangeStart.toInt(), plan.rangeEndExclusive.toInt())

    /**
     * 强校验：重新解析剪辑产物，逐样本核对
     * 1) 样本大小与源一致；2) 样本内容与源对应样本逐字节一致；
     * 3) 样本偏移与其在 mdat 中的实际位置（累积大小）一致。
     */
    private fun verifyClipContent(
        clip: ByteArray,
        parsed2: ParsedMp4,
        plan: ClipPlan,
        source: ByteArray
    ) {
        for (c in plan.tracks) {
            val outTrack = parsed2.tracks.first { it.trackId == c.track.trackId }
            assertEquals("样本数应一致", c.sampleCount, outTrack.sampleCount)
            var cum = 0L
            for (k in 0 until outTrack.sampleCount) {
                val srcIdx = c.fromSample + k
                assertEquals(
                    "样本大小不一致 k=$k",
                    c.track.sampleSizes[srcIdx], outTrack.sampleSizes[k]
                )
                // 偏移应等于 mdat 起点 + 前面样本的累积大小（单 chunk 布局）
                assertEquals(
                    "样本位置与累积布局不一致 k=$k",
                    outTrack.sampleOffsets[0] + cum, outTrack.sampleOffsets[k]
                )
                val src = source.copyOfRange(
                    c.track.sampleOffsets[srcIdx].toInt(),
                    (c.track.sampleOffsets[srcIdx] + c.track.sampleSizes[srcIdx]).toInt()
                )
                val dst = clip.copyOfRange(
                    outTrack.sampleOffsets[k].toInt(),
                    (outTrack.sampleOffsets[k] + outTrack.sampleSizes[k]).toInt()
                )
                assertTrue("样本内容不一致 track=${c.track.handlerType} k=$k", src.contentEquals(dst))
                cum += outTrack.sampleSizes[k]
            }
        }
    }

    /* ══════════════ 解析 ══════════════ */

    @Test
    fun `解析合成文件 - 轨道与时长正确`() {
        val file = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(file)
        assertNotNull(scan.moovBox)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)

        assertEquals(2, parsed.tracks.size)
        assertEquals(30000L, parsed.durationMs) // 750 帧 × 512 / 12800 = 30s

        val video = parsed.videoTrack!!
        assertTrue(video.isVideo)
        assertEquals(vCount, video.sampleCount)
        assertEquals(vTs, video.timescale)
        assertEquals(30000L, video.durationInTimescale * 1000 / vTs)

        val audio = parsed.tracks.first { it.isAudio }
        assertEquals(aCount, audio.sampleCount)
        assertEquals(aTs, audio.timescale)

        // 所有样本偏移必须落在 mdat 内
        val mdatDataStart = (scan.moovStart + scan.moovSize + 8).toLong()
        for (t in parsed.tracks) {
            for (i in 0 until t.sampleCount) {
                assertTrue(t.sampleOffsets[i] >= mdatDataStart)
                assertTrue(t.sampleOffsets[i] + t.sampleSizes[i] <= file.size)
            }
        }
    }

    /* ══════════════ 规划 ══════════════ */

    @Test
    fun `规划 - 起点吸附到关键帧且覆盖所有轨道`() {
        val file = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(file)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)

        // 目标 3100ms -> 吸附到 3000ms 处的关键帧（样本序号 76，0-based 75）
        val snap = Mp4Clipper.snappedWindowMs(parsed, 3100, 8000)
        assertEquals(3000L, snap.first)

        val plan = Mp4Clipper.planMs(parsed, 3100, 8000)
        val videoClip = plan.tracks.first { it.track.isVideo }
        val audioClip = plan.tracks.first { it.track.isAudio }

        // 起点：3000ms 整；终点：DTS <= 8000ms 的最后样本（index 200）
        assertEquals(75, videoClip.fromSample)
        assertEquals(201, videoClip.toSampleExclusive)
        assertEquals(3000L, videoClip.track.sampleDts[75] * 1000 / vTs)
        assertTrue(audioClip.sampleCount > 0)

        // 字节区间 = 所有轨道保留样本的最小偏移到最大终点
        var minOff = Long.MAX_VALUE
        var maxEnd = Long.MIN_VALUE
        for (c in plan.tracks) {
            minOff = minOf(minOff, c.track.sampleOffsets[c.fromSample])
            val last = c.toSampleExclusive - 1
            maxEnd = maxOf(maxEnd, c.track.sampleOffsets[last] + c.track.sampleSizes[last])
        }
        assertEquals(minOff, plan.rangeStart)
        assertEquals(maxEnd, plan.rangeEndExclusive)
    }

    @Test
    fun `规划 - 时间窗超出时长时取到最后一个样本`() {
        val file = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(file)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)
        val plan = Mp4Clipper.planMs(parsed, 0, 999_999)
        val videoClip = plan.tracks.first { it.track.isVideo }
        assertEquals(0, videoClip.fromSample)
        assertEquals(vCount, videoClip.toSampleExclusive)
    }

    @Test
    fun `规划 - 零长窗口抛出异常`() {
        val file = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(file)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)
        try {
            Mp4Clipper.planMs(parsed, 2000, 2000) // 起止相同
            fail("应抛出 Mp4ClipException")
        } catch (e: Mp4ClipException) {
            // expected
        }
    }

    /* ══════════════ 重建 ══════════════ */

    @Test
    fun `重建 - 产物可再次解析且样本内容逐字节一致`() {
        val file = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(file)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)

        val plan = Mp4Clipper.planMs(parsed, 3000, 8000)
        val out = ByteArrayOutputStream()
        Mp4Clipper.writeClip(out, parsed, plan, segmentCopy(extractSegment(file, plan), plan.rangeStart))
        val clip = out.toByteArray()

        assertTrue("剪辑产物应远小于原文件", clip.size < file.size)

        val scan2 = Mp4FileLocator.scanHead(clip)
        assertNotNull(scan2.moovBox)
        val parsed2 = Mp4Parser.parse(scan2.ftypBox ?: ByteArray(0), scan2.moovBox!!)

        assertEquals(2, parsed2.tracks.size)
        val v2 = parsed2.videoTrack!!
        val a2 = parsed2.tracks.first { it.isAudio }
        val vPlan = plan.tracks.first { it.track.isVideo }
        val aPlan = plan.tracks.first { it.track.isAudio }

        assertEquals(vPlan.sampleCount, v2.sampleCount)
        assertEquals(aPlan.sampleCount, a2.sampleCount)

        // 产物视频时长 ≈ 5 秒（126 帧 × 512 / 12800）
        val v2DurMs = v2.durationInTimescale * 1000 / v2.timescale
        assertTrue("视频时长应约 5 秒，实际 $v2DurMs", v2DurMs in 4900..5100)

        // 每个样本的偏移都必须落在 mdat 内且不越界
        val mdatDataStart2 = scan2.moovStart + scan2.moovSize + 8
        for (t in parsed2.tracks) {
            for (i in 0 until t.sampleCount) {
                assertTrue("样本偏移越界 track=${t.handlerType} i=$i", t.sampleOffsets[i] >= mdatDataStart2)
                assertTrue(t.sampleOffsets[i] + t.sampleSizes[i] <= clip.size)
            }
        }

        // 原文件关键帧样本 75（DTS 38400）成为新文件第 1 个样本，且关键帧周期保持
        assertEquals(1, v2.syncSamples!!.first())
        assertEquals(9, v2.syncSamples!!.size)

        // 逐样本核对内容与位置
        verifyClipContent(clip, parsed2, plan, file)
    }

    /* ══════════════ moov 定位 ══════════════ */

    @Test
    fun `定位 - 头部完整 moov`() {
        val file = buildSyntheticMp4()
        val head = file.copyOf(64 * 1024)
        val scan = Mp4FileLocator.scanHead(head)
        assertNotNull(scan.ftypBox)
        assertNotNull(scan.moovBox)
        assertEquals(scan.ftypBox!!.size.toLong(), scan.moovStart) // moov 紧跟 ftyp
        assertFalse(scan.hasMoof)
    }

    @Test
    fun `定位 - 截断的头部 moov 能报告精确大小`() {
        val file = buildSyntheticMp4()
        val head = file.copyOf(2048) // 只够容纳 ftyp 和 moov 的开头
        val scan = Mp4FileLocator.scanHead(head)
        assertNull(scan.moovBox)
        val fullScan = Mp4FileLocator.scanHead(file)
        assertEquals(fullScan.ftypBox!!.size.toLong(), scan.moovStart)
        assertEquals(fullScan.moovSize, scan.moovSize)
    }

    @Test
    fun `定位 - 尾部 moov（非 faststart）`() {
        val file = buildSyntheticMp4()
        // 前半段包含头部 moov，不应误报；这里只验证 mdat 中部缓冲找不到 moov
        val tailStart = file.size / 2
        val tail = file.copyOfRange(tailStart, file.size)
        assertNull(Mp4FileLocator.locateMoovInTail(tail, tailStart.toLong(), file.size.toLong()))

        // 构造 moov 在文件尾部的文件，验证能从尾部缓冲定位并解析
        val trailing = buildFileWithTrailingMoov()
        val tStart = trailing.size - 8192
        val found = Mp4FileLocator.locateMoovInTail(
            trailing.copyOfRange(tStart, trailing.size),
            tStart.toLong(),
            trailing.size.toLong()
        )
        assertNotNull(found)
        val (start, size) = found!!
        val headScan = Mp4FileLocator.scanHead(trailing)
        val parsed = Mp4Parser.parse(
            headScan.ftypBox ?: ByteArray(0),
            trailing.copyOfRange(start.toInt(), (start + size).toInt())
        )
        assertEquals(2, parsed.tracks.size)
    }

    /** 构造 moov 在文件尾部的 MP4：ftyp + 大 mdat + moov（本测试只验证定位逻辑） */
    private fun buildFileWithTrailingMoov(): ByteArray {
        val inner = buildSyntheticMp4()
        val scan = Mp4FileLocator.scanHead(inner)
        val ftyp = scan.ftypBox!!
        val moov = scan.moovBox!!
        val filler = ByteArray(200 * 1024)
        val bb = java.nio.ByteBuffer.allocate(8)
        bb.putInt(filler.size + 8)
        bb.put("mdat".toByteArray(Charsets.US_ASCII))
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(bb.array())
        out.write(filler)
        out.write(moov)
        return out.toByteArray()
    }

    /* ══════════════ 真实文件端到端（本机有 ffmpeg 才跑） ══════════════ */

    @Test
    fun `真实 ffmpeg 文件 - 剪辑产物可被 ffprobe 解析且可完整解码`() {
        val ffmpeg = findTool("ffmpeg") ?: return skip("未找到 ffmpeg")
        val ffprobe = findTool("ffprobe") ?: return skip("未找到 ffprobe")

        val dir = Files.createTempDirectory("mp4clip").toFile()
        val src = File(dir, "src.mp4")
        val clip = File(dir, "clip.mp4")

        // 30s 测试视频：每秒一个关键帧，带 AAC 音轨，faststart（moov 在头部）
        val gen = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=30:size=320x240:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-g", "10", "-keyint_min", "10", "-sc_threshold", "0",
            "-c:a", "aac", "-b:a", "32k", "-shortest",
            "-movflags", "+faststart",
            src.absolutePath
        ).redirectErrorStream(true).start()
        gen.inputStream.readBytes()
        assertEquals("ffmpeg 生成失败", 0, gen.waitFor())
        assertTrue("源文件过小", src.length() > 10_000)

        val srcBytes = src.readBytes()
        val scan = Mp4FileLocator.scanHead(srcBytes)
        assertNotNull("faststart 文件头部应含 moov", scan.moovBox)
        val parsed = Mp4Parser.parse(scan.ftypBox!!, scan.moovBox!!)
        assertTrue(parsed.hasVideoTrack)
        val srcDurMs = parsed.durationMs
        assertTrue("源时长异常: $srcDurMs", srcDurMs in 29_000..31_500)

        val plan = Mp4Clipper.planMs(parsed, 10_000, 15_000)
        clip.outputStream().use { out ->
            Mp4Clipper.writeClip(
                out, parsed, plan,
                segmentCopy(extractSegment(srcBytes, plan), plan.rangeStart)
            )
        }

        // 逐样本核对内容与位置（在 ffprobe/解码前先验证容器自洽性）
        val clipBytes = clip.readBytes()
        val scan2 = Mp4FileLocator.scanHead(clipBytes)
        val parsed2 = Mp4Parser.parse(scan2.ftypBox ?: ByteArray(0), scan2.moovBox!!)
        verifyClipContent(clipBytes, parsed2, plan, srcBytes)

        // ffprobe 结构校验
        val probe = ProcessBuilder(
            ffprobe, "-v", "error", "-print_format", "json",
            "-show_format", "-show_streams", clip.absolutePath
        ).redirectErrorStream(true).start()
        val probeOut = String(probe.inputStream.readBytes())
        assertEquals("ffprobe 校验失败: $probeOut", 0, probe.waitFor())
        assertTrue("缺少视频流: $probeOut", probeOut.contains("\"video\""))
        assertTrue("缺少音频流: $probeOut", probeOut.contains("\"audio\""))
        val duration = Regex("\"duration\"\\s*:\\s*\"([0-9.]+)\"").findAll(probeOut)
            .map { it.groupValues[1].toDouble() }.maxOrNull()
        assertNotNull(duration)
        // 流复制剪辑：5s 目标，关键帧对齐后应在 4~6.5s 内
        assertTrue("剪辑时长异常: $duration", duration!! in 4.0..6.5)

        // 全量解码验证（stderr 无输出 = 每一帧都可解）
        val decode = ProcessBuilder(
            ffmpeg, "-v", "error", "-i", clip.absolutePath, "-f", "null", "-"
        ).redirectErrorStream(true).start()
        val decodeOut = String(decode.inputStream.readBytes())
        assertEquals("解码存在错误: $decodeOut", 0, decode.waitFor())
        assertTrue("解码报错: $decodeOut", decodeOut.isBlank())

        dir.deleteRecursively()
    }

    private fun skip(reason: String) {
        org.junit.Assume.assumeTrue("跳过：$reason", false)
    }

    private fun findTool(name: String): String? {
        val osName = System.getProperty("os.name") ?: ""
        val isWindows = osName.contains("Windows", ignoreCase = true)
        val ext = if (isWindows) ".exe" else ""
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            val f = File(dir, "$name$ext")
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        if (isWindows) {
            val root = File(System.getProperty("user.home"), "AppData/Local/Microsoft/WinGet/Packages")
            val pkgs = root.listFiles { d -> d.isDirectory && d.name.startsWith("Gyan.FFmpeg") } ?: return null
            for (pkg in pkgs) {
                pkg.walkTopDown().forEach { f ->
                    if (f.isFile && f.name == "$name$ext") return f.absolutePath
                }
            }
        }
        return null
    }
}
