package com.yuno.tv.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY priority DESC, id ASC") fun observe(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources ORDER BY priority DESC, id ASC") suspend fun all(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun get(id: String): SourceEntity?
    @Query("UPDATE sources SET status = :status, error = :error, warningCount = COALESCE(:warnings, warningCount), lastSuccessTime = COALESCE(:success, lastSuccessTime) WHERE id = :id")
    suspend fun status(id: String, status: String, error: String?, warnings: Int?, success: Long?)
    @Upsert suspend fun upsert(source: SourceEntity)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun delete(id: String)
}
@Dao
interface CacheDao {
    @Query("SELECT * FROM config_cache") suspend fun all(): List<ConfigCacheEntity>
    @Query("SELECT * FROM config_cache WHERE sourceId = :id") suspend fun get(id: String): ConfigCacheEntity?
    @Upsert suspend fun upsert(cache: ConfigCacheEntity)
    @Query("DELETE FROM config_cache WHERE sourceId = :id OR sourceId LIKE :id || ':%'") suspend fun delete(id: String)
    @Query("DELETE FROM config_cache WHERE EXISTS (SELECT 1 FROM sources WHERE local = 0 AND (config_cache.sourceId = sources.id OR config_cache.sourceId LIKE sources.id || ':%'))") suspend fun clearRemote()
}
@Dao
interface LibraryDao {
    @Query("SELECT * FROM history ORDER BY updateTime DESC LIMIT 500") fun history(): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history WHERE id = :id") suspend fun history(id: String): HistoryEntity?
    @Upsert suspend fun saveHistory(entity: HistoryEntity)
    @Query("DELETE FROM history WHERE id = :id") suspend fun deleteHistory(id: String)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("SELECT * FROM favorites ORDER BY createTime DESC") fun favorites(): Flow<List<FavoriteEntity>>
    @Query("SELECT COUNT(*) FROM favorites WHERE id = :id") suspend fun hasFavorite(id: String): Int
    @Upsert suspend fun saveFavorite(entity: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE id = :id") suspend fun deleteFavorite(id: String)
    @Query("SELECT * FROM search_history ORDER BY updateTime DESC LIMIT 20") fun searches(): Flow<List<SearchHistoryEntity>>
    @Upsert suspend fun saveSearch(entity: SearchHistoryEntity)
    @Query("DELETE FROM search_history WHERE keyword NOT IN (SELECT keyword FROM search_history ORDER BY updateTime DESC LIMIT 20)") suspend fun trimSearches()
    @Query("DELETE FROM search_history") suspend fun clearSearches()
    @Query("SELECT id FROM live_favorites ORDER BY createTime DESC") fun liveFavorites(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM live_favorites WHERE id = :id") suspend fun hasLiveFavorite(id: String): Int
    @Upsert suspend fun saveLiveFavorite(entity: LiveFavoriteEntity)
    @Query("DELETE FROM live_favorites WHERE id = :id") suspend fun deleteLiveFavorite(id: String)
    @Query("SELECT id FROM live_recent ORDER BY updateTime DESC LIMIT 30") fun liveRecent(): Flow<List<String>>
    @Upsert suspend fun saveLiveRecent(entity: LiveRecentEntity)
}
