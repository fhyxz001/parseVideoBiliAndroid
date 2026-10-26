package com.videoparser.bili

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCookieTest {

    private val sessData = "abcdef1234567890%2C1799999999%2C7c1f2e3d4b*11"
    private val jct = "0123456789abcdef0123456789abcdef"

    @Test
    fun sanitize_keepsStandardHeader() {
        val raw = "SESSDATA=$sessData; bili_jct=$jct; buvid3=AAA-BBB; DedeUserID=12345"
        val cleaned = BiliCookie.sanitize(raw)
        assertEquals(
            "SESSDATA=$sessData; bili_jct=$jct; buvid3=AAA-BBB; DedeUserID=12345",
            cleaned
        )
    }

    @Test
    fun sanitize_dropsDescriptionsAndLineBreaks() {
        val raw = """
            Cookie:
            SESSDATA=$sessData
            bili_jct=$jct
            说明：这段文字不是 Cookie，请忽略。
        """.trimIndent()
        val pairs = BiliCookie.parsePairs(raw)
        assertEquals(sessData, pairs[BiliCookie.KEY_SESSDATA])
        assertEquals(jct, pairs[BiliCookie.KEY_JUMP])
        assertEquals(2, pairs.size)
    }

    @Test
    fun parsePairs_readsJson() {
        val json = """{"SESSDATA": "$sessData", "bili_jct": "$jct", "DedeUserID": "42"}"""
        val pairs = BiliCookie.parsePairs(json)
        assertEquals(sessData, pairs[BiliCookie.KEY_SESSDATA])
        assertEquals(jct, pairs[BiliCookie.KEY_JUMP])
        assertEquals("42", pairs[BiliCookie.KEY_DEDE_USER_ID])
    }

    @Test
    fun parsePairs_readsBareValues() {
        // 只粘 SESSDATA 的值
        assertEquals(
            sessData,
            BiliCookie.parsePairs(sessData)[BiliCookie.KEY_SESSDATA]
        )
        // 只粘 bili_jct 的值（32 位十六进制）
        assertEquals(
            jct,
            BiliCookie.parsePairs(jct)[BiliCookie.KEY_JUMP]
        )
    }

    @Test
    fun parsePairs_rejectsGarbage() {
        assertTrue(BiliCookie.parsePairs("今天天气不错").isEmpty())
        assertTrue(BiliCookie.parsePairs("").isEmpty())
        assertTrue(BiliCookie.parsePairs(null).isEmpty())
    }

    @Test
    fun buildHeader_ordersKnownKeysFirst() {
        val header = BiliCookie.buildHeader(
            mapOf(
                "foo" to "1",
                BiliCookie.KEY_JUMP to jct,
                BiliCookie.KEY_SESSDATA to sessData
            )
        )
        assertEquals("SESSDATA=$sessData; bili_jct=$jct; foo=1", header)
    }

    @Test
    fun apply_addsDeviceIdForLoginCookie() {
        BiliCookie.apply("SESSDATA=$sessData; bili_jct=$jct")
        assertTrue(BiliCookie.isLoggedIn())
        val pairs = BiliCookie.parsePairs(BiliCookie.current)
        val buvid = pairs[BiliCookie.KEY_BUVUID]
        assertTrue("buvid3 应被自动补齐：${BiliCookie.current}", !buvid.isNullOrBlank())
        assertTrue(buvid!!.endsWith("infoc"))
    }

    @Test
    fun apply_keepsExistingDeviceId() {
        BiliCookie.apply("SESSDATA=$sessData; buvid3=EXISTING-BUVID")
        assertEquals(
            "EXISTING-BUVID",
            BiliCookie.parsePairs(BiliCookie.current)[BiliCookie.KEY_BUVUID]
        )
    }

    @Test
    fun apply_guestCookieStaysGuest() {
        BiliCookie.apply("buvid3=ONLY-BUVID")
        assertFalse(BiliCookie.isLoggedIn())
        BiliCookie.apply("")
        assertFalse(BiliCookie.isLoggedIn())
        assertEquals("", BiliCookie.current)
    }

    @Test
    fun mask_hidesSecrets() {
        BiliCookie.apply("SESSDATA=$sessData; bili_jct=$jct; DedeUserID=42")
        val mask = BiliCookie.mask()
        assertFalse(mask.contains(sessData))
        assertFalse(mask.contains(jct))
        assertTrue(mask.startsWith("SESSDATA="))
        assertTrue(mask.contains("bili_jct="))
    }

    @Test
    fun redact_replacesSessData() {
        val text = "请求失败 SESSDATA=$sessData; bili_jct=$jct"
        val redacted = BiliCookie.redact(text)
        assertFalse(redacted.contains(sessData))
        assertTrue(redacted.contains("SESSDATA=***"))
    }

    @Test
    fun generateBuvid3_looksLikeReal() {
        val a = BiliCookie.generateBuvid3()
        val b = BiliCookie.generateBuvid3()
        assertNotEquals(a, b)
        assertTrue("buvid3=$a", a.endsWith("infoc"))
        // 36 位 UUID + 5 位随机数 + infoc = 50
        assertEquals(50, a.length)
    }

    @Test
    fun rememberQuality_onlyForLoggedIn() {
        BiliCookie.restorePreferences(0)
        BiliCookie.rememberQuality(112, loggedIn = true)
        assertEquals(112, BiliCookie.preferredQuality)
        // 换了低清视频后记住新的档位（下次解析仍会从 1080P+ 起试）
        BiliCookie.rememberQuality(64, loggedIn = true)
        assertEquals(64, BiliCookie.preferredQuality)
        // 游客不记录
        BiliCookie.rememberQuality(120, loggedIn = false)
        assertEquals(64, BiliCookie.preferredQuality)
    }

    /* ══════════════ 解析器与清晰度 ══════════════ */

    @Test
    fun mapPlayUrl_prefersDurlAndReadsQuality() {
        val json = JSONObject(
            """
            {
              "quality": 80,
              "accept_quality": [80, 64, 32],
              "durl": [{"order": 1, "url": "https://upos.example.com/1080.mp4?deadline=1"}]
            }
            """.trimIndent()
        )
        val play = BiliParser.mapPlayUrl(json)
        assertEquals("https://upos.example.com/1080.mp4?deadline=1", play.url)
        assertEquals(80, play.quality)
        assertEquals(listOf(80, 64, 32), play.accepts)
    }

    @Test
    fun mapPlayUrl_fallsBackToDash() {
        val json = JSONObject(
            """
            {
              "quality": 64,
              "dash": {"video": [{"id": 116, "baseUrl": "https://upos.example.com/dash-video.m4s"}]}
            }
            """.trimIndent()
        )
        val play = BiliParser.mapPlayUrl(json)
        assertEquals("https://upos.example.com/dash-video.m4s", play.url)
        assertEquals(116, play.quality)
    }

    @Test
    fun mapPlayUrl_usesRequestedQualityWhenMissing() {
        val json = JSONObject(
            """{"durl": [{"url": "https://upos.example.com/a.mp4"}]}"""
        )
        assertEquals(112, BiliParser.mapPlayUrl(json, requestedQuality = 112).quality)
    }

    @Test
    fun qualityHelpers() {
        assertTrue(BiliParser.needsLoginFor(80))
        assertTrue(BiliParser.needsLoginFor(112))
        assertFalse(BiliParser.needsLoginFor(64))
        assertEquals("1080P", BiliParser.qualityLabel(80))
        assertEquals("1080P+", BiliParser.qualityLabel(112))
        // 登录后的候选档位必须以 1080P+ 起步
        assertTrue(BiliParser.LOGIN_QUALITY_LADDER.first() >= BiliParser.QUALITY_1080P)
    }

    @Test
    fun authErrors_askForCookie() {
        assertEquals("登录状态无效，请在设置里重新配置 Cookie", BiliParser.apiErrorMessage(-401, ""))
        assertTrue(BiliParser.apiErrorMessage(-352, "").contains("Cookie"))
    }
}
