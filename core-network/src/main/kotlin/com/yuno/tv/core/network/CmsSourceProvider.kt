package com.yuno.tv.core.network

import com.google.gson.*
import com.yuno.tv.core.SourceException
import com.yuno.tv.core.rethrowCancellation
import kotlinx.coroutines.*
import com.yuno.tv.core.model.*
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Streaming
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface CmsApi {
    @Streaming @GET suspend fun query(@Url url: String, @QueryMap params: Map<String, String>, @HeaderMap headers: Map<String, String>): ResponseBody
}
data class CmsResponse(val list: List<CmsVod>, val categories: List<UnifiedCategory>)
data class CmsVod(val value: JsonObject)

class SourceProviderRegistry(network: NetworkClient, cacheDirectory: java.io.File? = null, private val pluginProvider: ((UnifiedSite) -> SourceProvider)? = null) {
    private val api = Retrofit.Builder().baseUrl("https://localhost/").client(network.http.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()).build().create(CmsApi::class.java)
    val healthChecker = SourceHealthChecker(cacheDirectory?.resolve("source-health.json"))
    val health = healthChecker.health
    private val detailCache = MediaContentCache(cacheDirectory)
    fun provider(site: UnifiedSite): SourceProvider {
        if (!site.supported) throw SourceException(ErrorCode.UNSUPPORTED)
        val delegate = when(site.capability.kind) {
            CapabilityKind.DIRECT_MEDIA -> DirectMediaSourceProvider(site)
            CapabilityKind.DRPY_JS, CapabilityKind.SPIDER_JAR -> pluginProvider?.invoke(site) ?: throw SourceException(ErrorCode.UNSUPPORTED)
            else -> CmsSourceProvider(site, api)
        }
        return object : SourceProvider {
            override suspend fun resolvePlayback(episode: UnifiedEpisode, line: UnifiedLine) = healthChecker.measure(site, "play") { delegate.resolvePlayback(episode, line) }
            override suspend fun getHome() = healthChecker.measure(site, "home") { delegate.getHome() }
            override suspend fun search(keyword: String) = healthChecker.measure(site, "search") { delegate.search(keyword) }
            override suspend fun getCategories() = delegate.getCategories()
            override suspend fun getCategory(categoryId: String, page: Int) = delegate.getCategory(categoryId, page)
            override suspend fun getDetail(vodId: String): UnifiedVod {
                try { return healthChecker.measure(site, "detail") { delegate.getDetail(vodId) }.also { detailCache.saveDetail(it) } }
                catch (error: Exception) { error.rethrowCancellation(); return detailCache.detail(site.id, vodId) ?: throw error }
            }
        }
    }
}
typealias ProviderRegistry = SourceProviderRegistry

/** Only statically registered, trusted implementations may use this future extension point. */
interface SourcePluginFactory { val kind: CapabilityKind; fun create(site: UnifiedSite): SourceProvider }
class DirectMediaSourceProvider(private val site: UnifiedSite) : SourceProvider {
    private fun media() = UnifiedVod(site.id, site.id, site.name, site.name, lines = listOf(UnifiedLine("direct", site.name,
        listOf(UnifiedEpisode("direct", site.name, site.api, site.metadata.headers)))))
    override suspend fun getHome() = HomeResult(listOf(media()), emptyList())
    override suspend fun search(keyword: String) = if (site.name.contains(keyword, true)) listOf(media()) else emptyList()
    override suspend fun getCategories() = emptyList<UnifiedCategory>()
    override suspend fun getCategory(categoryId: String, page: Int) = if (page == 1) listOf(media()) else emptyList()
    override suspend fun getDetail(vodId: String) = media()
}

class CmsSourceProvider(private val site: UnifiedSite, private val api: CmsApi) : SourceProvider {
    override suspend fun getHome(): HomeResult = withContext(Dispatchers.Default) {
        val result = request(mapOf("ac" to "detail", "pg" to "1"))
        val categories = if (result.categories.isNotEmpty()) result.categories else getCategories()
        HomeResult(result.list.map { it.unified(includeLines = false) }, categories)
    }
    override suspend fun search(keyword: String): List<UnifiedVod> = withContext(Dispatchers.Default) {
        request(mapOf("ac" to "detail", "wd" to keyword)).list.map { it.unified(includeLines = false) }
    }
    override suspend fun getCategories(): List<UnifiedCategory> = request(emptyMap()).categories
    override suspend fun getCategory(categoryId: String, page: Int): List<UnifiedVod> =
        withContext(Dispatchers.Default) { request(mapOf("ac" to "detail", "t" to categoryId, "pg" to page.toString())).list.map { it.unified(includeLines = false) } }
    override suspend fun getDetail(vodId: String): UnifiedVod = withContext(Dispatchers.Default) {
        request(mapOf("ac" to "detail", "ids" to vodId)).list.firstOrNull()?.unified() ?: throw SourceException(ErrorCode.EMPTY)
    }
    private suspend fun request(params: Map<String, String>): CmsResponse = withContext(Dispatchers.IO) {
        val base = site.api.toHttpUrlOrNull() ?: throw SourceException(ErrorCode.INVALID_URL)
        val url = base.newBuilder().apply { params.keys.forEach { removeAllQueryParameters(it) } }.build().toString()
        val response = try { api.query(url, params, site.metadata.headers) } catch (_: retrofit2.HttpException) { throw SourceException(ErrorCode.NETWORK) }
        val body = response.use { response ->
            val bytes = response.byteStream().use { stream ->
                val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk); if (count < 0) break
                    if (buffer.size() + count > NetworkClient.MAX_CONFIG_BYTES) throw SourceException(ErrorCode.CONFIG_FORMAT)
                    buffer.write(chunk, 0, count)
                }; buffer.toByteArray()
            }; NetworkClient.decodeUtf8(bytes)
        }
        com.yuno.tv.core.ensureSafeJson(body)
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrElse { throw SourceException(ErrorCode.CONFIG_FORMAT) }
        CmsResponse(root.array("list").take(500).filter { it.isJsonObject }.map { CmsVod(it.asJsonObject) },
            root.array("class").filter { it.isJsonObject }.map { UnifiedCategory(it.asJsonObject.text("type_id"), it.asJsonObject.text("type_name")) })
    }
    private fun CmsVod.unified(includeLines: Boolean = true): UnifiedVod {
        val names = value.text("vod_play_from").splitToSequence("$$$").take(20).toList()
        val playlists = value.text("vod_play_url").splitToSequence("$$$").take(20).toList()
        val lines = if (includeLines) playlists.take(20).mapIndexedNotNull { index, list ->
            val episodes = list.splitToSequence('#').filter { it.isNotBlank() }.take(1000).toList().mapIndexedNotNull { i, entry ->
                val separator = if (entry.trim().startsWith("http", ignoreCase = true)) -1 else entry.indexOf('$')
                val url = if (separator >= 0) entry.substring(separator + 1).trim() else entry.trim()
                if (url.toHttpUrlOrNull() == null) return@mapIndexedNotNull null
                UnifiedEpisode("$index:$i", if (separator >= 0) entry.substring(0, separator) else (i + 1).toString(), url, site.metadata.headers)
            }
            if (episodes.isEmpty()) null else UnifiedLine(index.toString(), names.getOrNull(index)?.ifBlank { (index + 1).toString() }
                ?: (index + 1).toString(), episodes)
        } else emptyList()
        return UnifiedVod(value.text("vod_id"), site.id, site.name, value.text("vod_name"),
            value.text("vod_pic"), value.text("vod_pic_slide"), value.text("vod_year"), value.text("vod_area"),
            value.text("type_name"), value.text("vod_director"), value.text("vod_actor"), value.text("vod_score"),
            value.text("vod_content").take(12000).replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").trim(), value.text("vod_remarks"), lines)
    }
}
private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
