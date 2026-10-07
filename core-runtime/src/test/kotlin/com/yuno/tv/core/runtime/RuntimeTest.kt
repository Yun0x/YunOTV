package com.yuno.tv.core.runtime

import com.google.gson.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.*

private class MemoryHost:RuntimeHost {
    val values=mutableMapOf<String,String>()
    override fun http(url:String,options:String)="{\"content\":\"<a href='/video'>Title</a>\",\"code\":200,\"headers\":{}}"
    override fun storage(operation:String,key:String,value:String):String = when(operation){"get"->values[key].orEmpty();"set"->{values[key]=value;""};else->{values.remove(key);""}}
}
class JsSandboxTest {
    @Test fun noJavaShellOrFiles() { JsEngine(DrpyBridge(MemoryHost())).use { js ->
        assertEquals("undefined,undefined,undefined,undefined",js.evaluate("[typeof Packages,typeof java,typeof JavaAdapter,typeof load].join(',')"))
        assertEquals("undefined",js.evaluate("typeof __bridge.getClass"))
        assertEquals("3",js.evaluate("1+2"))
    } }
}
class JsTimeoutTest {
    @Test(timeout=3000) fun infiniteLoopCanBeTerminatedEvenWhenCaught() {
        val start=System.nanoTime()
        try { JsEngine(DrpyBridge(MemoryHost()),100).use { it.evaluate("try{while(true){}}catch(e){while(true){}}") };fail() } catch(_:JsDeadline){}
        assertTrue((System.nanoTime()-start)/1_000_000<2000)
    }
    @Test fun oversizeScriptRejected() { try { JsEngine(DrpyBridge(MemoryHost())).evaluate(" ".repeat(RuntimePolicy.JS_BYTES+1));fail() }catch(_:IllegalArgumentException){} }
}
class DrpyBridgeTest {
    @Test fun htmlSelectorsAndRelativeUrls() {
        val bridge=DrpyBridge(MemoryHost());val html="<section><a href='/v'>one</a><a href='/w'>two</a></section>"
        assertEquals("one",bridge.call("pdfh",listOf(html,"section&&a&&Text")))
        assertEquals(2,JsonParser.parseString(bridge.call("pdfa",listOf(html,"a"))).asJsonArray.size())
        assertEquals("https://example.test/v",bridge.call("pd",listOf(html,"a&&href","https://example.test/a")))
    }
    @Test fun cryptoRoundTripsAndHashes() {
        val crypto=CryptoBridge(); assertEquals("900150983cd24fb0d6963f7d28e17f72",crypto.digest("MD5","abc"))
        assertEquals("测试",crypto.base64(crypto.base64("测试",false),true))
        val value=crypto.aes("a legal test","0123456789abcdef","abcdef0123456789",true)
        assertEquals("a legal test",crypto.aes(value,"0123456789abcdef","abcdef0123456789",false))
    }
    @Test fun cookiesAndStorageNeverLeakAcrossSources() {
        val server=MockWebServer();server.start()
        try {
            val one=RuntimeHttpBridge(OkHttpClient());val two=RuntimeHttpBridge(OkHttpClient())
            server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie","session=private; Path=/"));one.http(server.url("/").toString(),"{}");server.takeRequest()
            server.enqueue(MockResponse().setBody("ok"));one.http(server.url("/").toString(),"{}");assertEquals("session=private",server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("ok"));two.http(server.url("/").toString(),"{}");assertNull(server.takeRequest().getHeader("Cookie"))
            one.storage("set","token","private");assertEquals("",two.storage("get","token",""))
        }finally{server.shutdown()}
    }
}
class DrpyRuntimeTest {
    @Test fun executesAllSixExportedEntrypoints() {
        val bundle=JsonObject().apply { addProperty("engine","""
            var state='';export default {init:function(s){state=s;},home:function(){return {class:[{type_id:'1',type_name:'Test'}]};},homeVod:function(){return {list:[]};},category:function(t,p){return {list:[],page:p};},search:function(k){return {list:[],keyword:k};},detail:function(id){return {list:[{vod_id:id,vod_name:state}]};},play:function(f,id){return {parse:0,url:id};}};
        """);addProperty("rule","Fixture") }
        DrpyRuntime(MemoryHost()).use { rt ->
            for(operation in listOf("home","homeVod","category","search","detail","play")){
                val args=JsonArray().apply { add("legal");add("http://example.test/clip.mp4") }
                assertTrue(rt.execute(bundle,operation,args,1000).startsWith('{'))
            }
        }
    }
    @Test fun realEngineWithControlledRule() {
        val path=System.getenv("YUNO_DRPY_ENGINE").orEmpty()
        org.junit.Assume.assumeTrue(path.isNotEmpty())
        val bundle=JsonObject().apply { addProperty("engine",java.io.File(path).readText());addProperty("rule","var rule={title:'Fixture',host:'https://example.test',class_name:'Test',class_url:'1',url:'/fyclass/fypage',searchUrl:'/search?wd=**',搜索:'js:setResult([])',推荐:'js:setResult([])',一级:'js:setResult([])',二级:'*'};") }
        DrpyRuntime(MemoryHost()).use { rt -> rt.execute(bundle,"home",JsonArray().apply{add(true)},3000).let { assertTrue(it,it.contains("Test")) };rt.execute(bundle,"search",JsonArray().apply{add("legal");add(false);add(1)},3000).let { assertTrue(it,it.contains("list")) } }
    }
}
class DrpyProviderTest {
    @Test fun opaqueEpisodeIdSurvivesForProviderPlayer() {
        val site=UnifiedSite("s","s","Test",3,"csp_Test",origin=SourceOrigin("c","Test","local:",1))
        val result=SpiderResultParser(site).detail("""{"list":[{"vod_id":"x","vod_name":"Test","vod_play_from":"line","vod_play_url":"Episode${'$'}opaque-id"}]}""")
        assertEquals("opaque-id",result.lines.single().episodes.single().url)
    }
}
class SpiderClassResolverTest {
    @Test fun resolvesOnlyKnownClassPattern() { assertEquals("com.github.catvod.spider.XBPQ",SpiderClassResolver().resolve("csp_XBPQ"));for(bad in listOf("csp_../x","java.lang.Runtime","csp_")){try{SpiderClassResolver().resolve(bad);fail()}catch(_:RuntimeFailure){}} }
}
class SpiderResultParserTest {
    @Test fun requiresStructuredJsonAndParsesProviderHeaders() {
        val site=UnifiedSite("s","s","Test",3,"csp_Test",origin=SourceOrigin("c","Test","local:",1));val parser=SpiderResultParser(site)
        assertEquals("http://example.test/a.m3u8",parser.play("""{"url":"http://example.test/a.m3u8","parse":0,"header":{"Referer":"https://example.test/"}}""",PlaybackCandidate("opaque")).url)
        for(bad in listOf("<html>","[1]","{}")){try{parser.detail(bad);fail()}catch(_:Exception){}}
    }
}
class JarRepositoryTest {
    @Test fun cancelledDownloadReleasesTheRequest()=runBlocking {
        val server=MockWebServer();server.start();val dir=Files.createTempDirectory("yuno-cancel").toFile()
        try {
            server.enqueue(MockResponse().setBody("late").setBodyDelay(5,java.util.concurrent.TimeUnit.SECONDS))
            val repo=ArtifactRepository(dir,OkHttpClient(),1024,4096)
            val start=System.nanoTime()
            try{kotlinx.coroutines.withTimeout(100){repo.get(server.url("/").toString())};fail()}catch(_:kotlinx.coroutines.TimeoutCancellationException){}
            assertTrue((System.nanoTime()-start)/1_000_000<1000)
        }finally{server.shutdown();dir.deleteRecursively()}
    }
    @Test fun conditionalDownloadAndLastGoodFallback()=runBlocking {
        val server=MockWebServer();server.start();val dir=Files.createTempDirectory("yuno-artifact").toFile()
        try {
            val repo=ArtifactRepository(dir,OkHttpClient(),1024,4096)
            server.enqueue(MockResponse().setBody("artifact").addHeader("ETag","v1"));val first=repo.get(server.url("/jar").toString());server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(304));assertEquals(first.first.hash,repo.get(first.first.url,true).first.hash);assertEquals("v1",server.takeRequest().getHeader("If-None-Match"))
            server.enqueue(MockResponse().setResponseCode(404));assertEquals(first.first.hash,repo.get(first.first.url,true).first.hash)
            server.takeRequest()
            first.second.writeText("corrupted cache")
            server.enqueue(MockResponse().setBody("replacement"));val replacement=repo.get(first.first.url,true)
            assertNotEquals(first.first.hash,replacement.first.hash);assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertTrue(first.second.name.matches(Regex("[a-f0-9]{64}")))
            server.enqueue(MockResponse().setBody("x".repeat(1025)));try{repo.get(server.url("/large").toString());fail()}catch(_:RuntimeFailure){}
        }finally{server.shutdown();dir.deleteRecursively()}
    }
}
class SpiderRuntimeTest {
    @Test fun streamingDexHeaderIntegrityAcceptsValidChecksumsAndRejectsTampering() {
        val dex=ByteArray(112)
        "dex\n035\u0000".toByteArray().copyInto(dex)
        val header=java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(32,112);header.putInt(36,112);header.putInt(40,0x12345678)
        java.security.MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32,dex.size)).copyInto(dex,12)
        header.putInt(8,java.util.zip.Adler32().apply { update(dex,12,dex.size-12) }.value.toInt())
        fun jar():ByteArray=java.io.ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("classes.dex"));it.write(dex);it.closeEntry()
        } }.toByteArray()
        JarVerifier().validate(jar())
        dex[100]=1
        try{JarVerifier().validate(jar());fail()}catch(error:RuntimeFailure){assertEquals("INVALID_DEX",error.reason)}
    }
    @Test fun compressedBombIsRejectedByStreamingHostVerifier() {
        val out=java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("large.dat"));val block=ByteArray(8192)
            repeat(4097){zip.write(block)};zip.closeEntry()
        }
        assertTrue(out.size()<100_000)
        try { JarVerifier().validate(out.toByteArray());fail() }catch(error:RuntimeFailure){assertEquals("INPUT_LIMIT",error.reason)}
    }
    @Test fun invalidAndTraversalJarsRejectedBeforeLoading() {
        val verifier=JarVerifier()
        val out=java.io.ByteArrayOutputStream();ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../classes.dex"));it.write(byteArrayOf(100,101,120,10));it.closeEntry() }
        for(bytes in listOf(byteArrayOf(),"bad".toByteArray(),out.toByteArray())){try{verifier.verify(bytes);fail()}catch(_:RuntimeFailure){}}
    }
}
class SpiderInventoryTest {
    @Test fun sharedJarUrlKeepsSiteAndClassCountsSeparate() {
        val origin=SourceOrigin("c","c","local:",1)
        val sites=listOf("csp_A","csp_B","csp_A").mapIndexed { index,api -> UnifiedSite("$index","$index","Fixture",3,api,origin=origin,capability=SourceCapability(CapabilityKind.SPIDER_JAR,CapabilityStatus.PARTIAL),metadata=SourceMetadata(if(index==0)"HTTPS://EXAMPLE.TEST/a.jar;md5;ignored"else"https://example.test/a.jar")) }
        val result=SpiderInventory.from(sites)
        assertEquals(3,result.sites);assertEquals(1,result.jarUrls);assertEquals(2,result.classes);assertEquals(3,result.families.single().sites)
    }
}
