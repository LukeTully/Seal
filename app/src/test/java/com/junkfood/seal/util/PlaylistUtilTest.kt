package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUtilTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val channelUrl = "https://www.youtube.com/channel/example"

    // Shape observed with yt-dlp 2026.08.19 for the reported channel: tab playlists
    // contain video entries, and the top-level entries have no video URL.
    private fun channelResult(): PlaylistResult =
        json
            .decodeFromString<PlaylistResult>(
                """
            {
              "_type": "playlist", "title": "Channel", "original_url": "$channelUrl",
              "entries": [
                {"_type": "playlist", "title": "Videos", "webpage_url": "$channelUrl/videos",
                 "entries": [
                   {"_type": "url", "title": "Video 1", "url": "https://example.com/video1"},
                   {"_type": "url", "title": "Video 2", "url": "https://example.com/video2"}
                 ]},
                {"_type": "playlist", "title": "Shorts", "webpage_url": "$channelUrl/shorts",
                 "entries": [
                   {"_type": "url", "title": "Short 1", "url": "https://example.com/short1"}
                 ]}
              ]
            }
            """
                    .trimIndent()
            )
            .flattenEntries()

    @Test
    fun channelTabsBecomeVideoRowsWithLocalPlaylistIndices() {
        val entries = channelResult().entries!!

        assertEquals(listOf("Video 1", "Video 2", "Short 1"), entries.map { it.title })
        assertEquals(listOf(1, 2, 1), entries.map { it.playlistIndex })
        assertEquals(
            listOf("$channelUrl/videos", "$channelUrl/videos", "$channelUrl/shorts"),
            entries.map { it.sourcePlaylistUrl },
        )
        assertTrue(entries.all { it.url != null })
    }

    @Test
    fun selectingFlattenedRowTargetsTheCorrectTabAndItem() {
        val result = channelResult()
        val tasks =
            TaskFactory.createWithPlaylistResult(
                playlistUrl = channelUrl,
                indexList = listOf(2, 3),
                playlistResult = result,
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
            )

        assertEquals(listOf("$channelUrl/videos", "$channelUrl/shorts"), tasks.map { it.task.url })
        assertEquals(listOf(2, 1), tasks.map { (it.task.type as Task.TypeInfo.Playlist).index })
    }

    @Test
    fun flatPlaylistPreservesExtractorIndicesAndFallsBackToRowPosition() {
        val result =
            PlaylistResult(
                    webpageUrl = "https://example.com/playlist",
                    entries =
                        listOf(
                            PlaylistEntry(title = "First", playlistIndex = 4),
                            PlaylistEntry(title = "Second"),
                        ),
                )
                .flattenEntries()

        assertEquals(listOf(4, 2), result.entries!!.map { it.playlistIndex })
        assertTrue(result.entries.all { it.sourcePlaylistUrl == result.webpageUrl })
    }

    @Test
    fun selectedItemForcesPlaylistEvenForWatchUrls() {
        val request = YoutubeDLRequest("https://example.com/watch?video=1&list=2")
        request.selectPlaylistItem(3)

        assertEquals("3", request.getOption("--playlist-items"))
        assertTrue(request.hasOption("--yes-playlist"))
        assertFalse(request.hasOption("--no-playlist"))
    }

    @Test
    fun singleVideoStillDisablesPlaylist() {
        val request = YoutubeDLRequest("https://example.com/watch?video=1&list=2")
        request.selectPlaylistItem(null)

        assertTrue(request.hasOption("--no-playlist"))
        assertFalse(request.hasOption("--playlist-items"))
        assertFalse(request.hasOption("--yes-playlist"))
    }
}
