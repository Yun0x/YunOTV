package com.yuno.tv.core.network

import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchAggregatorTest {
    private fun site(id: String) = UnifiedSite(id, id, id, 1, "https://example.test/$id", origin = SourceOrigin(id, id, "", 1))
    private fun provider(search: suspend () -> List<UnifiedVod>) = object : SourceProvider {
        override suspend fun search(keyword: String) = search()
        override suspend fun getHome(): HomeResult = error("unused")
        override suspend fun getCategories(): List<UnifiedCategory> = error("unused")
        override suspend fun getCategory(categoryId: String, page: Int): List<UnifiedVod> = error("unused")
        override suspend fun getDetail(vodId: String): UnifiedVod = error("unused")
    }
    @Test fun exceptionAndTimeoutDoNotRemoveSuccessfulResults() = runBlocking {
        val aggregator = SearchAggregator({ value -> when (value.id) {
            "error" -> provider { throw IOException("private URL must not escape") }
            "slow" -> provider { delay(500); emptyList() }
            else -> provider { listOf(UnifiedVod("1", "ok", "OK", "Film")) }
        } }, timeoutMs = 30)
        val result = aggregator.search("Film", listOf(site("ok"), site("error"), site("slow")))
        assertEquals(1, result.results.size)
        assertEquals(ErrorCode.NETWORK, result.errors["error"])
        assertEquals(ErrorCode.TIMEOUT, result.errors["slow"])
    }
    @Test fun crossSourceDuplicatesKeepAllPlayableVariants() = runBlocking {
        val aggregator = SearchAggregator({ source -> provider {
            val vod = UnifiedVod("1", source.id, source.name, "Film", year = "2026", type = "Movie", area = "CN")
            listOf(vod, vod)
        } })
        val result = aggregator.search("Film", listOf(site("a"), site("b")))
        assertEquals(1, result.results.size)
        assertEquals(2, result.variants.values.single().size)
    }
    @Test fun progressiveResultsKeepTheirRepresentativeAndFocusKey() = runBlocking {
        val aggregator = SearchAggregator({ source -> provider {
            if (source.id == "slow") delay(100)
            listOf(UnifiedVod("1", source.id, source.name, "Same", year = "2026"))
        } })
        val frames = aggregator.stream("Same", listOf(site("slow"), site("fast"))).toList()
        val first = frames.first { it.results.isNotEmpty() }.results.single()
        assertEquals("fast", first.sourceId)
        assertEquals(first.identity, frames.last().results.single().identity)
        assertEquals(2, frames.last().variants.getValue(first.identity).size)
    }
    @Test fun invalidUtf8ReturnsEncodingError() {
        try { NetworkClient.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28)); fail("must reject bad encoding") }
        catch (error: com.yuno.tv.core.SourceException) { assertEquals(ErrorCode.ENCODING, error.code) }
    }
}
