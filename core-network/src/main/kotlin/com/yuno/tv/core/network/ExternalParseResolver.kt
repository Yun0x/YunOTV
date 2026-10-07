package com.yuno.tv.core.network

import com.google.gson.*
import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit JSON HTTP parsers only. HTML sniffing and DRM/account escalation have no execution path. */
class ExternalParseResolver(client: OkHttpClient) {
    private val http=client.newBuilder().callTimeout(8,TimeUnit.SECONDS).build()
    suspend fun resolve(candidate: PlaybackCandidate, parses: List<UnifiedParse>): PlaybackCandidate {
        if(!candidate.parseRequired) return candidate
        for(parse in parses.filter { it.type==1 || it.type==2 }.take(3)) {
            try {
                return withContext(Dispatchers.IO) {
                    val base=parse.url.toHttpUrlOrNull() ?: throw SourceException(ErrorCode.INVALID_URL)
                    val url=if(parse.url.endsWith('=')) parse.url+URLEncoder.encode(candidate.url,"UTF-8") else base.newBuilder().setQueryParameter("url",candidate.url).build().toString()
                    val request=Request.Builder().url(url).apply { candidate.headers.forEach { (k,v) -> if(k.matches(Regex("[A-Za-z0-9-]+")) && !v.contains('\n') && !v.contains('\r')) header(k,v) } }.build()
                    val call=http.newCall(request)
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, error: java.io.IOException) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    val resolved = response.use { parseResponse(it, candidate) }
                                    if (continuation.isActive) continuation.resume(resolved)
                                } catch (error: Exception) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                            }
                        })
                    }
                }
            } catch(error:Exception){if(error is CancellationException)throw error}
        }
        throw SourceException(ErrorCode.UNSUPPORTED)
    }
    private fun parseResponse(response: Response, candidate: PlaybackCandidate): PlaybackCandidate {
        if (!response.isSuccessful) throw SourceException(ErrorCode.NETWORK)
        val body = response.body ?: throw SourceException(ErrorCode.PLAYBACK)
        if (body.contentType()?.toString()?.contains("html", true) == true) throw SourceException(ErrorCode.UNSUPPORTED)
        val input = body.byteStream().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (out.size() + count > 256 * 1024) throw SourceException(ErrorCode.CONFIG_FORMAT)
                out.write(chunk, 0, count)
            }
            out.toString("UTF-8")
        }
        ensureSafeJson(input)
        val json = JsonParser.parseString(input).asJsonObject
        val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
        val address = data.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.toHttpUrlOrNull()
            ?: throw SourceException(ErrorCode.PLAYBACK)
        val headers = (data.get("header") ?: data.get("headers"))?.takeIf { it.isJsonObject }?.asJsonObject
            ?.entrySet()?.filter { it.value.isJsonPrimitive && it.key.matches(Regex("[A-Za-z0-9-]+")) &&
                it.value.asString.none { char -> char == '\n' || char == '\r' } }
            ?.associate { it.key to it.value.asString }.orEmpty()
        return candidate.copy(url=address.toString(), parseRequired=false, headers=candidate.headers+headers)
    }

}
class PlaybackCoordinator(private val registry:SourceProviderRegistry,client:OkHttpClient) {
    private val external=ExternalParseResolver(client)
    suspend fun resolve(request:PlaybackRequest,config:UnifiedConfig):ResolvedPlayback {
        val episode=request.episode ?: throw SourceException(ErrorCode.INVALID_URL)
        val line=request.vod.lines.getOrNull(request.lineIndex) ?: throw SourceException(ErrorCode.INVALID_URL)
        val site=config.sites.firstOrNull { it.id==request.vod.sourceId }
        val candidate=if(site!=null && !request.live)registry.provider(site).resolvePlayback(episode,line) else PlaybackCandidate(episode.url,headers=episode.headers,contentType=episode.contentType)
        val parsed=external.resolve(candidate,config.parses)
        val headers=parsed.headers + parsed.userAgent.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty() + parsed.referer.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }.orEmpty()
        return PlaybackResolver().resolve(episode.copy(url=parsed.url,headers=headers,contentType=parsed.contentType))
    }
}
/** Bounded, once-per-line fallback. Corresponding episode names take precedence over raw indexes. */
class PlaybackFallback(private val maximum:Int=3, private val score:(PlaybackRequest,Int)->Int={_,_->0}) {
    private val attempted=linkedSetOf<Int>()
    fun reset(){attempted.clear()}
    fun next(request:PlaybackRequest):PlaybackRequest? {
        attempted += request.lineIndex
        if(attempted.size>=maximum || request.live)return null
        val next=request.vod.lines.indices.filter { it !in attempted && request.vod.lines[it].episodes.isNotEmpty() }
            .maxByOrNull { score(request,it) } ?: return null
        val episodes=request.vod.lines[next].episodes
        val match=episodes.indexOfFirst { it.name==request.episode?.name }
        return request.copy(lineIndex=next,episodeIndex=if(match>=0)match else request.episodeIndex.coerceAtMost(episodes.lastIndex))
    }
}
