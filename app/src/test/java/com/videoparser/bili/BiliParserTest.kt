package com.videoparser.bili

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BiliParserTest {

    @Test
    fun extractUrl_fromShareText() {
        val text = "【Never Gonna Give You Up】 https://b23.tv/AbCdEf 复制链接打开 B 站"
        assertEquals("https://b23.tv/AbCdEf", BiliParser.extractUrl(text))
    }

    @Test
    fun extractUrl_trimsChinesePunctuation() {
        val text = "快看这个 https://www.bilibili.com/video/BV1xx411c7mD。"
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            BiliParser.extractUrl(text)
        )
    }

    @Test
    fun extractUrl_returnsNullWhenNoLink() {
        assertNull(BiliParser.extractUrl("今天天气不错"))
    }

    @Test
    fun parseVideoId_readsBv() {
        val id = BiliParser.parseVideoId("https://www.bilibili.com/video/BV1GJ411x7h7?share_source=copy")
        assertEquals("BV1GJ411x7h7", id?.bvid)
        assertNull(id?.aid)
    }

    @Test
    fun parseVideoId_readsAv() {
        val id = BiliParser.parseVideoId("av170001")
        assertEquals(170001L, id?.aid)
        assertNull(id?.bvid)
    }

    @Test
    fun parseVideoId_readsAvFromUrl() {
        val id = BiliParser.parseVideoId("https://www.bilibili.com/video/av170001")
        assertEquals(170001L, id?.aid)
    }

    @Test
    fun parseVideoId_rejectsPlainText() {
        assertNull(BiliParser.parseVideoId("随便一段文字"))
    }

    @Test
    fun normalizeImageUrl_upgradesToHttps() {
        assertEquals(
            "https://i1.hdslb.com/bfs/archive/a.jpg",
            BiliParser.normalizeImageUrl("http://i1.hdslb.com/bfs/archive/a.jpg")
        )
        assertEquals(
            "https://i2.hdslb.com/bfs/face/b.jpg",
            BiliParser.normalizeImageUrl("//i2.hdslb.com/bfs/face/b.jpg")
        )
        assertEquals("", BiliParser.normalizeImageUrl(null))
    }

    @Test
    fun mapView_parsesCoreFields() {
        val json = JSONObject(
            """
            {
              "bvid": "BV1GJ411x7h7",
              "aid": 80433022,
              "title": "测试视频",
              "pic": "http://i1.hdslb.com/bfs/archive/a.jpg",
              "duration": 213,
              "cid": 137649199,
              "pages": [{"cid": 137649199, "page": 1, "part": "P1"}],
              "owner": {"mid": 486906719, "name": "UP主", "face": "//i2.hdslb.com/bfs/face/b.jpg"}
            }
            """.trimIndent()
        )

        val view = BiliParser.mapView(json)

        assertEquals("BV1GJ411x7h7", view.bvid)
        assertEquals(80433022L, view.aid)
        assertEquals("测试视频", view.title)
        assertEquals(213, view.durationSec)
        assertEquals(1, view.pageCount)
        assertEquals(137649199L, view.cid)
        assertEquals("UP主", view.author.name)
        assertEquals("https://i2.hdslb.com/bfs/face/b.jpg", view.author.avatar)
    }

    @Test
    fun mapView_multiPageCount() {
        val json = JSONObject(
            """
            {
              "bvid": "BV1xx411c7mD",
              "aid": 1,
              "title": "t",
              "pic": "",
              "duration": 10,
              "pages": [
                {"cid": 11, "page": 1},
                {"cid": 22, "page": 2}
              ],
              "owner": {"mid": 1, "name": "up"}
            }
            """.trimIndent()
        )

        val view = BiliParser.mapView(json)

        assertEquals(2, view.pageCount)
        assertEquals(11L, view.cid)
    }

    @Test
    fun mapView_withoutCidThrows() {
        val json = JSONObject("""{"bvid":"BV1xx411c7mD","aid":1}""")
        assertThrows(BiliException::class.java) { BiliParser.mapView(json) }
    }

    @Test
    fun mapPlayUrl_parsesDurl() {
        val json = JSONObject(
            """
            {
              "quality": 64,
              "durl": [{"order": 1, "url": "http://upos.example.com/a.mp4?deadline=1"}]
            }
            """.trimIndent()
        )

        val play = BiliParser.mapPlayUrl(json)

        assertEquals("https://upos.example.com/a.mp4?deadline=1", play.url)
        assertEquals(64, play.quality)
    }

    @Test
    fun mapPlayUrl_withoutDurlThrows() {
        val json = JSONObject("""{"quality": 64, "durl": []}""")
        assertThrows(BiliException::class.java) { BiliParser.mapPlayUrl(json) }
    }

    @Test
    fun qualityLabel_mapsKnownQualities() {
        assertEquals("720P", BiliParser.qualityLabel(64))
        assertEquals("1080P", BiliParser.qualityLabel(80))
        assertEquals("4K", BiliParser.qualityLabel(120))
        assertEquals("未知画质", BiliParser.qualityLabel(0))
    }

    @Test
    fun apiErrorMessage_mapsKnownCodes() {
        assertEquals("视频不存在或已被删除", BiliParser.apiErrorMessage(-404, ""))
        assertEquals("稿件不可见（可能已删除或设为私密）", BiliParser.apiErrorMessage(62002, ""))
        assertEquals("自定义错误", BiliParser.apiErrorMessage(-999, "自定义错误"))
    }

    @Test
    fun formatDuration_formatsMinutesAndHours() {
        assertEquals("03:33", BiliParser.formatDuration(213))
        assertEquals("1:02:03", BiliParser.formatDuration(3723))
        assertEquals("", BiliParser.formatDuration(0))
    }
}
