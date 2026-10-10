package com.junkfood.seal.util

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URI

internal fun YoutubeDLRequest.addCookieUserAgent(userAgent: String, url: String? = null) {
    if (userAgent.isEmpty()) return
    val host = runCatching { url?.let { URI(it).host } }.getOrNull()
    val isYoutube =
        host.equals("youtube.com", ignoreCase = true) ||
            host?.endsWith(".youtube.com", ignoreCase = true) == true ||
            host.equals("youtu.be", ignoreCase = true)
    val isMobileAgent =
        listOf("Android", "Mobile", "iPhone", "iPad").any {
            userAgent.contains(it, ignoreCase = true)
        }
    // YouTube serves mobile pages that its tab extractor cannot parse with WebView's UA.
    // Keep authentication, but let yt-dlp select its supported default UA for those requests.
    if (isYoutube && isMobileAgent) return
    addOption("--add-header", "User-Agent:$userAgent")
}
