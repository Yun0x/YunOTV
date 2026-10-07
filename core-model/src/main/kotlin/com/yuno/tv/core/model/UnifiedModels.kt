package com.yuno.tv.core.model

data class SourceOrigin(val sourceId: String, val sourceName: String, val originUrl: String, val priority: Int)
data class UnifiedConfig(
    val sites: List<UnifiedSite> = emptyList(), val lives: List<UnifiedLive> = emptyList(),
    val parses: List<UnifiedParse> = emptyList(), val warnings: List<ConfigWarning> = emptyList(), val spider: String = ""
)
data class UnifiedSite(
    val id: String, val key: String, val name: String, val type: Int, val api: String,
    val searchable: Boolean = true, val quickSearch: Boolean = true, val filterable: Boolean = false,
    val ext: String = "", val origin: SourceOrigin, val supported: Boolean = type == 1,
    val origins: List<SourceOrigin> = listOf(origin), val capability: SourceCapability = SourceCapability(),
    val metadata: SourceMetadata = SourceMetadata()
)
data class UnifiedLive(
    val id: String, val name: String, val url: String, val group: String = "IPTV",
    val logo: String = "", val epg: String = "", val origin: SourceOrigin,
    val origins: List<SourceOrigin> = listOf(origin)
)
data class UnifiedParse(val id: String, val name: String, val type: Int, val url: String,
    val ext: String = "", val origin: SourceOrigin)
data class UnifiedCategory(val id: String, val name: String)
data class UnifiedEpisode(val id: String, val name: String, val url: String, val headers: Map<String, String> = emptyMap(), val contentType: String = "")
data class UnifiedLine(val id: String, val name: String, val episodes: List<UnifiedEpisode>)
data class UnifiedVod(
    val id: String, val sourceId: String, val sourceName: String, val name: String,
    val poster: String = "", val background: String = "", val year: String = "", val area: String = "",
    val type: String = "", val director: String = "", val actors: String = "", val rating: String = "",
    val description: String = "", val remarks: String = "", val lines: List<UnifiedLine> = emptyList()
) { val identity: String get() = "$sourceId|$id" }
data class HomeResult(val recent: List<UnifiedVod>, val categories: List<UnifiedCategory>)
data class ConfigWarning(val code: ErrorCode, val entry: String = "")

enum class ErrorCode {
    NETWORK, TIMEOUT, DOWNLOAD, CONFIG_FORMAT, ENCODING, INVALID_URL, UNSUPPORTED,
    EMPTY, PLAYBACK, STORAGE, FILE_PICKER, UNKNOWN
}
sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Error(val code: ErrorCode) : NetworkResult<Nothing>
    data object Loading : NetworkResult<Nothing>
}

interface SourceProvider {
    suspend fun getHome(): HomeResult
    suspend fun search(keyword: String): List<UnifiedMediaItem>
    suspend fun getCategories(): List<UnifiedCategory>
    suspend fun getCategory(categoryId: String, page: Int): List<UnifiedMediaItem>
    suspend fun getDetail(vodId: String): UnifiedMediaDetail
}

data class SourceRecord(
    val id: String, val name: String, val url: String, val format: String = "AUTO",
    val local: Boolean = false, val enabled: Boolean = true, val priority: Int = 100,
    val lastSuccessTime: Long = 0L, val status: String = "NEW", val error: ErrorCode? = null,
    val warningCount: Int = 0
) { val origin: SourceOrigin get() = SourceOrigin(id, name, url, priority) }
data class ConfigCache(val sourceId: String, val body: String, val etag: String = "",
    val lastModified: String = "", val hash: String = "", val lastSuccessTime: Long = 0L,
    val url: String = "", val contentType: String = "", val lastAttemptTime: Long = 0L, val error: ErrorCode? = null, val latencyMs: Long = 0L)
data class HistoryRecord(
    val id: String, val vod: UnifiedVod, val lineIndex: Int, val episodeIndex: Int,
    val episodeName: String, val playUrl: String, val position: Long, val duration: Long,
    val progress: Float, val updateTime: Long
)
data class FavoriteRecord(val vod: UnifiedVod, val createTime: Long)
data class SearchHistory(val keyword: String, val updateTime: Long)
data class PlayerSettings(
    val speed: Float = 1f, val aspect: String = "FIT", val seekSeconds: Int = 10,
    val historyEnabled: Boolean = true, val autoNext: Boolean = true,
    val autoRefresh: Boolean = true, val theme: String = "DARK", val homeCategory: String = "recent",
    val bundleImported: Boolean = false, val lastDestination: String = "HOME"
)
data class PlaybackRequest(val vod: UnifiedVod, val lineIndex: Int = 0, val episodeIndex: Int = 0,
    val position: Long = 0L, val live: Boolean = false) {
    val episode: UnifiedEpisode? get() = vod.lines.getOrNull(lineIndex)?.episodes?.getOrNull(episodeIndex)
}
