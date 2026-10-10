package com.junkfood.seal.util

import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CookieRequestUtilTest {
    private val mobileAgent = "Mozilla/5.0 (Linux; Android 17; Pixel 7; wv) Chrome/147.0.0.0"

    @Test
    fun youtubeMobileCookieAgentUsesExtractorDefaultWithoutDroppingCookies() {
        listOf(
                "https://m.youtube.com/@22Minutes",
                "https://www.youtube.com/channel/example/videos",
                "https://youtube.com/playlist?list=example",
                "https://youtu.be/example",
            )
            .forEach { url ->
                val request = YoutubeDLRequest(url).addOption("--cookies", "cookies.txt")
                request.addCookieUserAgent(mobileAgent, url)

                assertFalse(request.hasOption("--add-header"))
                assertEquals("cookies.txt", request.getOption("--cookies"))
            }
    }

    @Test
    fun otherSitesAndLookalikeHostsKeepConfiguredAgent() {
        listOf(
                "https://example.com/video",
                "https://youtube.com.example.com/video",
                "https://notyoutube.com/video",
            )
            .forEach { url ->
                val request = YoutubeDLRequest(url)
                request.addCookieUserAgent(mobileAgent, url)
                assertEquals("User-Agent:$mobileAgent", request.getOption("--add-header"))
            }
    }

    @Test
    fun desktopYoutubeAgentAndCustomCommandsKeepExplicitAgent() {
        val desktopAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/147.0.0.0"
        val request = YoutubeDLRequest("https://www.youtube.com/@22Minutes")
        request.addCookieUserAgent(desktopAgent, "https://www.youtube.com/@22Minutes")
        assertEquals("User-Agent:$desktopAgent", request.getOption("--add-header"))

        val command = YoutubeDLRequest("https://www.youtube.com/@22Minutes")
        command.addCookieUserAgent(mobileAgent)
        assertEquals("User-Agent:$mobileAgent", command.getOption("--add-header"))
    }
}
