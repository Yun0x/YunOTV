package com.yuno.tv.core.runtime

import com.yuno.tv.core.model.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SpiderInventory(val sites:Int,val jarUrls:Int,val classes:Int,val families:List<JarFamily>) {
    data class JarFamily(val url:String,val sites:Int,val classes:List<String>)
    companion object {
        fun from(sites:List<UnifiedSite>):SpiderInventory {
            val spiders=sites.filter{it.capability.kind==CapabilityKind.SPIDER_JAR}
            val families=spiders.groupBy { it.metadata.spider.substringBefore(";md5;").trim().toHttpUrlOrNull()?.toString().orEmpty() }
                .filterKeys { it.isNotBlank() }.map { (url,values) -> JarFamily(url,values.size,values.mapNotNull{runCatching{SpiderClassResolver().resolve(it.api)}.getOrNull()}.distinct().sorted()) }.sortedByDescending { it.sites }
            return SpiderInventory(spiders.size,families.size,spiders.mapNotNull{runCatching{SpiderClassResolver().resolve(it.api)}.getOrNull()}.distinct().size,families)
        }
    }
}
