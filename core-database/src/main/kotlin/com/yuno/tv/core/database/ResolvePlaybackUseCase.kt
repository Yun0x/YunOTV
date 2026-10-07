package com.yuno.tv.core.database

import com.yuno.tv.core.model.*

/** A completed episode resumes at its next episode, if one exists. */
class ResolvePlaybackUseCase {
    operator fun invoke(vod: UnifiedVod, history: HistoryRecord?): PlaybackRequest {
        val line = (history?.lineIndex ?: 0).coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0))
        val count = vod.lines.getOrNull(line)?.episodes?.size ?: 0
        val episode = (history?.episodeIndex ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0))
        val completed = history != null && history.progress >= .95f
        return PlaybackRequest(vod, line, if (completed && episode + 1 < count) episode + 1 else episode,
            if (completed) 0L else history?.position ?: 0L)
    }
}
