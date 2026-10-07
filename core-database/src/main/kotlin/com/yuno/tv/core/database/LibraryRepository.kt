package com.yuno.tv.core.database

import androidx.room.withTransaction
import com.google.gson.Gson
import com.yuno.tv.core.model.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository(private val database: YunoDatabase) {
    private val dao = database.library()
    private val gson = Gson()
    val history = dao.history().map { rows -> rows.mapNotNull { it.model() } }.flowOn(Dispatchers.Default)
    val favorites = dao.favorites().map { rows -> rows.mapNotNull { row ->
        decode(row.vodJson)?.let { FavoriteRecord(it, row.createTime) }
    } }.flowOn(Dispatchers.Default)
    val searches = dao.searches().map { rows -> rows.map { SearchHistory(it.keyword, it.updateTime) } }
    val liveFavorites = dao.liveFavorites()
    val liveRecent = dao.liveRecent()
    suspend fun getHistory(identity: String): HistoryRecord? = withContext(Dispatchers.Default) { dao.history(identity)?.model() }
    suspend fun saveHistory(request: PlaybackRequest, position: Long, duration: Long, timestamp: Long = System.currentTimeMillis()) {
        if (request.live || request.episode == null) return
        val vod = request.vod
        val safePosition = position.coerceAtLeast(0)
        val safeDuration = duration.coerceAtLeast(0)
        database.withTransaction {
        if ((dao.history(vod.identity)?.updateTime ?: 0L) > timestamp) return@withTransaction
        dao.saveHistory(HistoryEntity(vod.identity, vod.id, vod.sourceId, vod.name, vod.poster,
            request.episode!!.name, request.episodeIndex, request.lineIndex, request.episode!!.url,
            safePosition, safeDuration, if (safeDuration > 0) (safePosition.toFloat() / safeDuration).coerceIn(0f, 1f) else 0f,
            timestamp, gson.toJson(vod)))
        }
    }
    suspend fun deleteHistory(id: String) = dao.deleteHistory(id)
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun toggleFavorite(vod: UnifiedVod) = database.withTransaction {
        if (dao.hasFavorite(vod.identity) > 0) dao.deleteFavorite(vod.identity)
        else dao.saveFavorite(FavoriteEntity(vod.identity, vod.id, vod.sourceId, vod.name,
            vod.poster, System.currentTimeMillis(), gson.toJson(vod)))
    }
    suspend fun saveSearch(keyword: String) = database.withTransaction {
        val value = keyword.trim().take(100)
        if (value.isNotEmpty()) { dao.saveSearch(SearchHistoryEntity(value, System.currentTimeMillis())); dao.trimSearches() }
    }
    suspend fun clearSearches() = dao.clearSearches()
    suspend fun toggleLiveFavorite(id: String) = database.withTransaction {
        if (dao.hasLiveFavorite(id) > 0) dao.deleteLiveFavorite(id)
        else dao.saveLiveFavorite(LiveFavoriteEntity(id, System.currentTimeMillis()))
    }
    suspend fun saveLiveRecent(id: String) = dao.saveLiveRecent(LiveRecentEntity(id, System.currentTimeMillis()))
    private fun decode(json: String): UnifiedVod? = runCatching { gson.fromJson(json, UnifiedVod::class.java) }.getOrNull()
    private fun HistoryEntity.model(): HistoryRecord? = decode(vodJson)?.let {
        HistoryRecord(id, it, lineIndex, episodeIndex, episodeName, playUrl, position, duration, progress, updateTime)
    }
}
