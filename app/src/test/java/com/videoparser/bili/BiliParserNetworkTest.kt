package com.videoparser.bili

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实网络集成测试：默认跳过，避免 CI 依赖 B 站可用性。
 *
 * 本地手动运行：
 *   BILI_NETWORK_TEST=1 ./gradlew :app:testDebugUnitTest
 * Windows PowerShell:
 *   $env:BILI_NETWORK_TEST='1'; .\gradlew.bat :app:testDebugUnitTest
 */
class BiliParserNetworkTest {

    private fun enabled() = System.getenv("BILI_NETWORK_TEST") == "1"

    @Test
    fun parseByWebUrl() = runBlocking {
        assumeTrue(enabled())
        val result = BiliParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7")

        assertTrue(result is BiliParseResult.Success)
        val data = (result as BiliParseResult.Success).data
        assertEquals("BV1GJ411x7h7", data.bvid)
        assertTrue(data.title.isNotBlank())
        assertTrue(data.videoUrl.startsWith("https://"))
        assertTrue(data.coverUrl.startsWith("https://"))
        assertTrue(data.author.name.isNotBlank())
        assertTrue(data.durationSec > 0)
        assertTrue(data.qualityLabel != "未知画质")
    }

    @Test
    fun parseByBareBv() = runBlocking {
        assumeTrue(enabled())
        val result = BiliParser.parse("BV1GJ411x7h7")
        assertTrue(result is BiliParseResult.Success)
    }

    @Test
    fun parseByAvNumber() = runBlocking {
        assumeTrue(enabled())
        val result = BiliParser.parse("av170001")
        assertTrue(result is BiliParseResult.Success)
        assertTrue((result as BiliParseResult.Success).data.bvid.startsWith("BV"))
    }

    /** 真实验证：B 站 mp4 支持 Range，且 moov 能被解析、能规划出片段区间 */
    @Test
    fun probeRealVideoSupportsRangeClip() = runBlocking {
        assumeTrue(enabled())
        val parsed = BiliParser.parse("BV1GJ411x7h7")
        assertTrue(parsed is BiliParseResult.Success)
        val data = (parsed as BiliParseResult.Success).data

        val probe = VideoClipper.probe(data.videoUrl)
        assertTrue("probe=$probe", probe is ClipProbe.Ready)
        val ready = probe as ClipProbe.Ready
        assertTrue(ready.durationMs > 0)

        val plan = Mp4Clipper.planMs(ready.parsed, 30_000L, 40_000L)
        assertTrue(plan.rangeEndExclusive > plan.rangeStart)
        assertTrue(plan.rangeSize > 0)
    }
}
