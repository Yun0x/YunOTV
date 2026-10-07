package com.yuno.tv.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(@PrimaryKey val id: String, val name: String, val url: String, val format: String,
    val local: Boolean, val enabled: Boolean, val priority: Int, val lastSuccessTime: Long,
    val status: String, val error: String?, val warningCount: Int)
@Entity(tableName = "config_cache")
data class ConfigCacheEntity(@PrimaryKey val sourceId: String, val body: String, val etag: String,
    val lastModified: String, val hash: String, val lastSuccessTime: Long,
    @androidx.room.ColumnInfo(defaultValue = "''") val fileName: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val url: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val contentType: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val lastAttemptTime: Long = 0,
    val error: String? = null, @androidx.room.ColumnInfo(defaultValue = "0") val latencyMs: Long = 0L)
@Entity(tableName = "history")
data class HistoryEntity(@PrimaryKey val id: String, val vodId: String, val sourceId: String,
    val name: String, val poster: String, val episodeName: String, val episodeIndex: Int,
    val lineIndex: Int, val playUrl: String, val position: Long, val duration: Long,
    val progress: Float, val updateTime: Long, val vodJson: String)
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val id: String, val vodId: String, val sourceId: String,
    val name: String, val poster: String, val createTime: Long, val vodJson: String)
@Entity(tableName = "search_history")
data class SearchHistoryEntity(@PrimaryKey val keyword: String, val updateTime: Long)
@Entity(tableName = "live_favorites")
data class LiveFavoriteEntity(@PrimaryKey val id: String, val createTime: Long)
@Entity(tableName = "live_recent")
data class LiveRecentEntity(@PrimaryKey val id: String, val updateTime: Long)
