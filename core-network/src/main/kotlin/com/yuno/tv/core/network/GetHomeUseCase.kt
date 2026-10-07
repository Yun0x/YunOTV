package com.yuno.tv.core.network

import com.yuno.tv.core.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MediaSection(val id: String, val title: String, val items: List<UnifiedVod>, val sourceId: String = "", val categoryId: String = "", val page: Int = 1, val hasMore: Boolean = true)
data class HomeContent(val recent: List<UnifiedVod>, val sections: List<MediaSection>, val errors: Map<String, ErrorCode>)
class GetHomeUseCase(private val registry: ProviderRegistry) {
    suspend operator fun invoke(sites: List<UnifiedSite>): HomeContent = stream(sites).last()
    /** Start with three ranked sources. Categories are fetched when their row becomes visible. */
    fun stream(sites: List<UnifiedSite>): Flow<HomeContent> = flow {
        var content = HomeContent(emptyList(), emptyList(), emptyMap())
        emit(content)
        for (site in registry.healthChecker.ordered(sites.filter { it.supported }).take(3)) {
            try {
                val home = withTimeout(12_000) { registry.provider(site).getHome() }
                content = content.copy(recent = (content.recent + home.recent).distinctBy { it.identity }.take(60),
                    sections = (content.sections + home.categories.take(12).map { category -> MediaSection("${site.id}:${category.id}", category.name,
                        emptyList(), site.id, category.id) }).distinctBy { it.title }.take(12))
            } catch (error: Exception) { error.rethrowCancellation(); content = content.copy(errors = content.errors + (site.name to error.friendlyCode())) }
            emit(content)
        }
    }
}
