package com.yuno.tv.core.runtime

import com.google.gson.*
import com.yuno.tv.core.model.*
import com.yuno.tv.core.ensureSafeJson

class SpiderResultParser(private val site: UnifiedSite) {
    private fun root(input: String): JsonObject {
        RuntimePolicy.requireSize(input.toByteArray(),RuntimePolicy.RESULT_BYTES); ensureSafeJson(input)
        val json=JsonParser.parseString(input)
        return if(json.isJsonArray)JsonObject().apply{add("list",json)}else json.takeIf { it.isJsonObject }?.asJsonObject ?: throw RuntimeFailure("INVALID_RESULT")
    }
    fun home(input: String): HomeResult {
        val json = root(input)
        return HomeResult(items(input),json.array("class").filter { it.isJsonObject }.take(100).map { UnifiedCategory(it.asJsonObject.text("type_id"),it.asJsonObject.text("type_name")) })
    }
    fun items(input: String): List<UnifiedVod> = root(input).array("list").filter { it.isJsonObject }.take(500).map { media(it.asJsonObject) }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    fun detail(input: String): UnifiedVod = items(input).firstOrNull() ?: throw RuntimeFailure("EMPTY_RESULT")
    fun play(input: String, original: PlaybackCandidate): PlaybackCandidate {
        val json = root(input)
        val headers = json.obj("header").entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
        return original.copy(url = json.text("url").ifBlank { original.url },parseRequired = json.text("parse")=="1" || json.text("jx")=="1",headers = original.headers + headers)
    }
    private fun media(value: JsonObject): UnifiedVod {
        val names = value.text("vod_play_from").split("$$$")
        val lines = value.text("vod_play_url").split("$$$").take(20).mapIndexedNotNull { index, playlist ->
            val episodes = playlist.splitToSequence('#').take(1000).filter { it.isNotBlank() }.mapIndexed { i, entry ->
                val sep = if (entry.startsWith("http",true)) -1 else entry.indexOf('$')
                UnifiedEpisode("$index:$i",if(sep>=0)entry.substring(0,sep)else(i+1).toString(),if(sep>=0)entry.substring(sep+1)else entry,site.metadata.headers)
            }.toList()
            if(episodes.isEmpty())null else UnifiedLine(index.toString(),names.getOrElse(index){(index+1).toString()},episodes)
        }
        return UnifiedVod(value.text("vod_id"),site.id,site.name,value.text("vod_name"),value.text("vod_pic"),value.text("vod_pic_slide"),value.text("vod_year"),value.text("vod_area"),value.text("type_name"),value.text("vod_director"),value.text("vod_actor"),value.text("vod_score"),value.text("vod_content").take(12000).replace(Regex("<[^>]+>")," "),value.text("vod_remarks"),lines)
    }
}
