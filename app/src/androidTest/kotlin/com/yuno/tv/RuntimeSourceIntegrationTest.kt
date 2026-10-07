package com.yuno.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import com.yuno.tv.core.model.*
import com.yuno.tv.core.runtime.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/** Metadata/resolution only: never decode or play third-party film content. Explicitly opt in. */
@RunWith(AndroidJUnit4::class)
class RuntimeSourceIntegrationTest {
    @Test fun representativeRuntimeSources()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("runtimeRemote")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sitesFile=context.filesDir.resolve("qa/runtime-sites.json")
        Assert.assertTrue(sitesFile.isFile)
        val sites=JsonParser.parseString(sitesFile.readText()).asJsonArray.map { Gson().fromJson(it,UnifiedSite::class.java) }
        val app=context.applicationContext as YunoApplication
        val reports=JsonArray()
        for((index,site) in sites.withIndex()) {
            val report=JsonObject().apply {addProperty("id",site.id);addProperty("name",site.name);addProperty("kind",site.capability.kind.name)}
            val provider=app.container.runtime.provider(site)
            var items=emptyList<UnifiedVod>();var detail:UnifiedVod?=null
            suspend fun check(name:String, action:suspend()->Boolean) {
                try{report.addProperty(name,withTimeout(45_000){action()})}catch(error:Exception){if(error is CancellationException && error !is TimeoutCancellationException)throw error;report.addProperty(name,false)}
            }
            check("inspect"){app.container.runtime.call(site,"inspect",JsonArray());true}
            check("home"){val home=provider.getHome();items=home.recent;if(items.isEmpty()&&home.categories.isNotEmpty())items=provider.getCategory(home.categories.first().id,1);items.isNotEmpty()}
            check("search"){val results=provider.search(items.firstOrNull()?.name?.take(8) ?: "电影");if(items.isEmpty())items=results;results.isNotEmpty()}
            check("detail"){detail=items.firstOrNull()?.let {provider.getDetail(it.id)};detail?.lines?.isNotEmpty()==true}
            check("play"){val line=detail?.lines?.firstOrNull() ?: return@check false;val result=provider.resolvePlayback(line.episodes.first(),line);!result.parseRequired&&(result.url.startsWith("https://")||result.url.startsWith("http://"))}
            val evidence=app.container.runtime.diagnostics.value[site.id]
            report.addProperty("artifactHash",evidence?.hash.orEmpty());report.addProperty("runtime",evidence?.runtime.orEmpty());report.addProperty("lastError",evidence?.lastError.orEmpty());report.addProperty("latencyMs",evidence?.latencyMs ?: 0)
            reports.add(report)
            context.filesDir.resolve("qa/runtime-results.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(reports))
            android.util.Log.i("YunoRuntimeQA","source_${index+1}_of_${sites.size}")
        }
    }
}
