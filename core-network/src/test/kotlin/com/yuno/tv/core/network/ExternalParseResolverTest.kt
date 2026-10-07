package com.yuno.tv.core.network
import com.yuno.tv.core.model.*
import com.yuno.tv.core.SourceException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
class ExternalParseResolverTest {
    @Test fun cancellingParserStopsTheHttpCall()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5,java.util.concurrent.TimeUnit.SECONDS))
            val parser=UnifiedParse("p","Test",1,server.url("/").toString(),origin=SourceOrigin("c","c","local:",1))
            val started=System.nanoTime()
            try { withTimeout(100) { ExternalParseResolver(OkHttpClient()).resolve(PlaybackCandidate("legal",true),listOf(parser)) };fail() }
            catch(_:TimeoutCancellationException){}
            assertTrue((System.nanoTime()-started)/1_000_000<1000)
        }finally{server.shutdown()}
    }
    @Test fun explicitJsonApiAndHeadersResolveWithoutFetchingMedia()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val resolver=ExternalParseResolver(OkHttpClient())
            val direct=PlaybackCandidate("https://example.test/a.mp4");assertEquals(direct,resolver.resolve(direct,emptyList()))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"url":"https://example.test/legal.mp4","header":{"Referer":"https://example.test/"}}"""))
            val parse=UnifiedParse("p","Test",1,server.url("/resolve?url=").toString(),origin=SourceOrigin("c","c","local:",1))
            val result=resolver.resolve(direct.copy(parseRequired=true),listOf(parse))
            assertFalse(result.parseRequired);assertEquals("https://example.test/legal.mp4",result.url);assertEquals("https://example.test/",result.headers["Referer"])
            assertEquals("https://example.test/a.mp4",server.takeRequest().requestUrl!!.queryParameter("url"));assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<video/>"));try{resolver.resolve(direct.copy(parseRequired=true),listOf(parse));fail()}catch(_:SourceException){}
        }finally{server.shutdown()}
    }
}
