package com.yuno.tv.core.database

import com.yuno.tv.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ResumeTest {
    private val vod = UnifiedVod("1", "source", "Source", "Series", lines = listOf(UnifiedLine("0", "Direct",
        (0..2).map { UnifiedEpisode(it.toString(), (it + 1).toString(), "https://example.test/$it.mp4") })))
    private fun history(progress: Float, episode: Int = 0) = HistoryRecord(vod.identity, vod, 0, episode, "1",
        vod.lines[0].episodes[episode].url, 43000, 100000, progress, 0)
    @Test fun resumesUnfinishedEpisode() {
        val request = ResolvePlaybackUseCase()(vod, history(.43f))
        assertEquals(0, request.episodeIndex); assertEquals(43000L, request.position)
    }
    @Test fun ninetyFivePercentMovesToNextEpisode() {
        val request = ResolvePlaybackUseCase()(vod, history(.95f))
        assertEquals(1, request.episodeIndex); assertEquals(0L, request.position)
    }
    @Test fun completedLastEpisodeStartsAgainWithoutOverflow() {
        val request = ResolvePlaybackUseCase()(vod, history(1f, 2))
        assertEquals(2, request.episodeIndex); assertEquals(0L, request.position)
    }
}
