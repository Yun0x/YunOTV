package com.yuno.tv.core.network
import com.yuno.tv.core.model.*
import com.yuno.tv.core.SourceException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import org.junit.Test
import org.junit.Assert.*
class PlaybackFallbackTest {
    @Test fun neverLoopsAndMatchesEpisodeName() {
        val vod=UnifiedVod("v","s","s","Film",lines=(0..4).map{UnifiedLine("$it","$it",listOf(UnifiedEpisode("e","Episode 1","http://example.test/a.mp4")))})
        val fallback=PlaybackFallback();var request=PlaybackRequest(vod)
        request=fallback.next(request)!!;assertEquals(1,request.lineIndex)
        request=fallback.next(request)!!;assertEquals(2,request.lineIndex);assertNull(fallback.next(request))
    }
}
class SourceHealthV2Test {
    @Test fun successOnHomeDoesNotHidePlaybackFailure()=runBlocking {
        val site=UnifiedSite("s","s","s",1,"http://example.test/",origin=SourceOrigin("c","c","local:",1));val health=SourceHealthChecker()
        try{health.measure(site,"play"){throw SourceException(ErrorCode.PLAYBACK)}}catch(_:SourceException){}
        health.measure(site,"home"){"ok"}
        assertEquals(SourceHealthStatus.DEGRADED,health.health.value["s"]!!.status)
        assertEquals(1,health.health.value["s"]!!.operations["play"]!!.failure)
    }
    @Test fun concurrentOutcomesAndLineEvidenceAreRetained()=runBlocking {
        val site=UnifiedSite("s","s","s",1,"http://example.test/",origin=SourceOrigin("c","c","local:",1))
        val health=SourceHealthChecker()
        coroutineScope { (1..40).map { async(Dispatchers.Default) { health.measure(site,"search") { delay(5) } } }.awaitAll() }
        assertEquals(40,health.health.value["s"]!!.operations["search"]!!.success)
        val request=PlaybackRequest(UnifiedVod("v","s","s","Film",lines=listOf(UnifiedLine("backup","Backup",listOf(UnifiedEpisode("e","1","http://example.test/a.mp4"))))))
        health.playback(site,request,true)
        assertTrue(health.lineScore("s","backup")>0)
    }
}
class TieredSearchTest {
    @Test fun healthyTierDoesNotFanOutToAllSources()=runBlocking {
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val origin=SourceOrigin("c","c","local:",1)
        val sites=(1..128).map{UnifiedSite("$it","$it","$it",1,"http://example.test/",origin=origin)}
        val aggregator=SearchAggregator({site ->object:SourceProvider{
            override suspend fun search(keyword:String):List<UnifiedVod>{calls.incrementAndGet();return (1..3).map { UnifiedVod("$it",site.id,site.name,"Film ${site.id} $it") }}
            override suspend fun getHome()=HomeResult(emptyList(),emptyList());override suspend fun getCategories()=emptyList<UnifiedCategory>();override suspend fun getCategory(categoryId:String,page:Int)=emptyList<UnifiedVod>();override suspend fun getDetail(vodId:String)=UnifiedVod(vodId,site.id,site.name,"Film")
        }})
        val frames=aggregator.stream("legal",sites).take(18).toList();assertEquals(16,calls.get());assertTrue(frames.last().finished);assertTrue(frames.size>2)
        assertNotEquals(MediaFingerprint.of(UnifiedVod("1","a","a","Film",year="2025")),MediaFingerprint.of(UnifiedVod("2","b","b","Film",year="2026")))
    }
}
