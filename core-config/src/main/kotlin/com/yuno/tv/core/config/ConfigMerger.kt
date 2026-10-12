package com.yuno.tv.core.config

import com.yuno.tv.core.model.*

class ConfigMerger {
    fun merge(configs: List<UnifiedConfig>): UnifiedConfig {
        val sites = configs.flatMap { it.sites }.groupBy { it.id }.values.map { group ->
            val sorted = group.sortedWith(compareByDescending<UnifiedSite> { it.origin.priority }.thenBy { it.origin.sourceId })
            sorted.first().copy(origins = sorted.flatMap { it.origins }.distinctBy { it.sourceId + "|" + it.originUrl })
        }.sortedWith(compareByDescending<UnifiedSite> { it.origin.priority }.thenBy { it.name })
        val lives = configs.flatMap { it.lives }.groupBy { it.id }.values.map { group ->
            val sorted = group.sortedWith(compareByDescending<UnifiedLive> { it.origin.priority }.thenBy { it.origin.sourceId })
            sorted.first().copy(origins = sorted.flatMap { it.origins }.distinctBy { it.sourceId + "|" + it.originUrl })
        }.sortedWith(compareBy<UnifiedLive> { it.group }.thenBy { it.name })
        val parses = configs.flatMap { it.parses }.sortedByDescending { it.origin.priority }.distinctBy { it.id }
        val playlists = configs.flatMap { it.playlists }.sortedByDescending { it.origin.priority }.distinctBy { it.id }
        return UnifiedConfig(sites, lives, parses, configs.flatMap { it.warnings }, playlists = playlists)
    }
}
