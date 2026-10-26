package com.videoparser.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDownloaderTest {

    @Test
    fun buildFileName_stripsIllegalCharacters() {
        assertEquals(
            "a b c_BV1xx411c7mD",
            VideoDownloader.buildFileName("a/b:c", "BV1xx411c7mD")
        )
    }

    @Test
    fun buildFileName_fallsBackWhenTitleBlank() {
        assertEquals(
            "bilibili_BV1xx411c7mD",
            VideoDownloader.buildFileName("   ", "BV1xx411c7mD")
        )
    }

    @Test
    fun buildFileName_keepsTitleWhenNoBvid() {
        assertEquals("视频标题", VideoDownloader.buildFileName("视频标题", ""))
    }
}
