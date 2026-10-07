package com.yuno.tv

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import com.yuno.tv.core.database.*
import com.yuno.tv.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun v1DataAndValidatorsSurviveAndBodiesLeaveRoom() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "yuno-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JsonParser.parseString(instrumentation.context.assets.open("database-v1.json").bufferedReader().use { it.readText() }).asJsonObject.getAsJsonObject("database")
        val vod = UnifiedVod("v1", "old", "Old", "保留历史", lines = listOf(UnifiedLine("1","Line",listOf(UnifiedEpisode("1","EP","https://example.test/v.mp4")))))
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema.getAsJsonArray("entities").forEach { entry ->
                val table = entry.asJsonObject
                db.execSQL(table.get("createSql").asString.replace("\u0024{TABLE_NAME}", table.get("tableName").asString))
            }
            db.execSQL("INSERT INTO sources VALUES ('old','Old','local://old.json','JSON',1,1,100,10,'OK',NULL,0)")
            db.execSQL("INSERT INTO config_cache VALUES ('old','{\"sites\":[]}','etag-old','date-old','hash-old',10)")
            val history = ContentValues().apply {
                put("id",vod.identity); put("vodId",vod.id); put("sourceId",vod.sourceId); put("name",vod.name); put("poster","")
                put("episodeName","EP"); put("episodeIndex",0); put("lineIndex",0); put("playUrl",vod.lines[0].episodes[0].url)
                put("position",4000); put("duration",10000); put("progress",.4); put("updateTime",10); put("vodJson",Gson().toJson(vod))
            }
            db.insertOrThrow("history",null,history)
            val favorite = ContentValues().apply { put("id",vod.identity); put("vodId",vod.id); put("sourceId",vod.sourceId); put("name",vod.name); put("poster",""); put("createTime",10); put("vodJson",Gson().toJson(vod)) }
            db.insertOrThrow("favorites",null,favorite)
            db.version = 1
        }
        val db = YunoDatabase.create(context,name)
        val folder = context.filesDir.resolve("migration-cache-test")
        try {
            val store = SourceStore(db,folder)
            val cache = store.cache("old")!!
            assertEquals("etag-old",cache.etag); assertEquals("{\"sites\":[]}",cache.body)
            assertEquals("",db.caches().get("old")!!.body)
            assertTrue(folder.listFiles()!!.any { it.extension == "json" })
            val library = LibraryRepository(db)
            assertEquals(4000L,library.getHistory(vod.identity)!!.position)
            assertEquals(vod.identity,library.favorites.first().single().vod.identity)
            val old = store.get("old")!!
            store.save(old.copy(name = "Edited", enabled = false, priority = 5))
            store.status("old", "REFRESHING")
            store.finishRefresh("old",cache,0,20)
            assertFalse(store.get("old")!!.enabled)
            assertEquals("Edited",store.get("old")!!.name)
            assertEquals(5,store.get("old")!!.priority)
        } finally { db.close(); context.deleteDatabase(name); folder.deleteRecursively() }
    }
}
