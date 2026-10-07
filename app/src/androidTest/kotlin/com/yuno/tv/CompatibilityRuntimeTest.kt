package com.yuno.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import com.yuno.tv.core.runtime.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompatibilityRuntimeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get()=context.filesDir.resolve("qa").apply { mkdirs() }
    private val host=RuntimeHttpBridge(okhttp3.OkHttpClient())
    private fun spec(operation:String,vararg args:Any)=JsonObject().apply{addProperty("sourceId","fixture");addProperty("api","csp_YunoFixture");addProperty("ext","http://10.0.2.2:18765/clip.mp4");addProperty("operation",operation);add("args",Gson().toJsonTree(args))}
    @Test fun isolatedJsExecutesAndRecoversAfterTimeout()=runBlocking {
        val worker=RuntimeIsolation(context,DrpyRuntimeService::class.java)
        val file=directory.resolve("fixture-js.json")
        fun bundle(script:String)=file.apply{writeText(JsonObject().apply{addProperty("engine",script);addProperty("rule","")}.toString())}
        val engine="export default{home:function(){return {list:[{vod_id:'1',vod_name:'Original'}]};},search:function(){while(true){}}};"
        try {
            assertTrue(worker.execute(bundle(engine),spec("home"),host).contains("Original"))
            val start=System.nanoTime()
            try{worker.execute(file,spec("search"),host);fail("loop not stopped")}catch(error:RuntimeFailure){assertTrue(error.reason in listOf("TIMEOUT","RUNTIME_TERMINATED"))}
            assertTrue((System.nanoTime()-start)/1_000_000<16_000)
            assertTrue(worker.execute(bundle(engine),spec("home"),host).contains("Original"))
            val sandbox="export default{home:function(){return {java:typeof java,packages:typeof Packages,loader:typeof load};}};"
            val result=worker.execute(bundle(sandbox),spec("home"),host)
            assertFalse(result.contains("function"));assertTrue(result.contains("undefined"))
        }finally{worker.reset()}
    }
    @Test fun isolatedSpiderExecutesRealDexApiAndRecovers()=runBlocking {
        val file=directory.resolve("fixture.jar");assertTrue("Run scripts/build-runtime-fixtures.sh and stage fixture.jar first",file.isFile)
        val worker=RuntimeIsolation(context,SpiderRuntimeService::class.java)
        try {
            for(operation in listOf("home","homeVod","category","search","detail","play")) {
                android.util.Log.i("YunoRuntimeQA","fixture_$operation")
                assertTrue(worker.execute(file,spec(operation,"legal","opaque"),host).startsWith('{'))
            }
            try{worker.execute(file,spec("search","crash"),host);fail()}catch(_:RuntimeFailure){}
            val start=System.nanoTime()
            try{worker.execute(file,spec("search","infinite"),host);fail()}catch(error:RuntimeFailure){assertEquals("RUNTIME_TERMINATED",error.reason)}
            assertTrue((System.nanoTime()-start)/1_000_000<16_000)
            assertTrue(worker.execute(file,spec("home"),host).contains("Original"))
            val invalid=spec("home");invalid.addProperty("api","csp_MissingClass")
            try{worker.execute(file,invalid,host);fail()}catch(error:RuntimeFailure){assertEquals("UNSUPPORTED_CLASS",error.reason)}
        }finally{worker.reset()}
    }
}
