package com.junkfood.seal.util

import com.yausername.youtubedl_android.YoutubeDLRequest

/** Channel URLs can contain several playlists (for example, Videos and Shorts). */
internal fun PlaylistResult.flattenEntries(): PlaylistResult {
    fun flatten(entries: List<PlaylistEntry>, playlistUrl: String?): List<PlaylistEntry> =
        entries.flatMapIndexed { index, entry ->
            if (entry.type == "playlist" || entry.entries != null) {
                flatten(entry.entries.orEmpty(), entry.webpageUrl ?: entry.url ?: playlistUrl)
            } else {
                listOf(
                    entry.copy(
                        playlistIndex = entry.playlistIndex ?: index + 1,
                        sourcePlaylistUrl = playlistUrl,
                    )
                )
            }
        }

    return copy(entries = flatten(entries.orEmpty(), originalUrl ?: webpageUrl))
}

internal fun YoutubeDLRequest.selectPlaylistItem(index: Int?) {
    if (index != null) {
        require(index > 0)
        addOption("--yes-playlist")
        addOption("--playlist-items", index)
    } else {
        addOption("--no-playlist")
    }
}
