package com.yuno.tv.core.network

import com.yuno.tv.core.SourceException
import com.yuno.tv.core.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PlaybackResolver {
    fun resolve(episode: UnifiedEpisode, metadata: SourceMetadata = SourceMetadata()): ResolvedPlayback {
        val url = episode.url.trim().toHttpUrlOrNull() ?: throw SourceException(ErrorCode.INVALID_URL)
        val path = url.encodedPath.lowercase()
        val type = episode.contentType.orEmpty()
        val kind = when {
            type.contains("mpegurl", true) || path.endsWith(".m3u8") -> PlaybackKind.HLS
            type == "video/mp4" || path.endsWith(".mp4") -> PlaybackKind.MP4
            listOf(".mkv", ".webm", ".ts", ".mp3", ".aac", ".flv").any { path.endsWith(it) } -> PlaybackKind.DIRECT
            path.endsWith(".html") || path.endsWith(".htm") || type.contains("text/html", true) -> PlaybackKind.PARSE_REQUIRED
            else -> PlaybackKind.UNKNOWN
        }
        if (kind == PlaybackKind.PARSE_REQUIRED) throw SourceException(ErrorCode.UNSUPPORTED)
        val headers = (metadata.headers.orEmpty() + episode.headers.orEmpty()).filter { (key,value) ->
            key.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\n') && !value.contains('\r')
        }
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "YunoTV/0.2"
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
        return ResolvedPlayback(url.toString(), headers, ua, referer, when(kind) {
            PlaybackKind.HLS -> "application/x-mpegURL"; PlaybackKind.MP4 -> "video/mp4"; else -> type
        }, kind)
    }
}
