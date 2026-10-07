package com.yuno.tv.core.config

import com.google.gson.*
import com.yuno.tv.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reclassifies the captured inventory without changing its remote input or claiming execution. */
class Phase3SnapshotTest {
    @Test fun phase2CmsCapabilityRemainsAvailable() {
        val path=System.getenv("YUNO_INVENTORY_DIR")
        assumeTrue(path!=null)
        val input=JsonParser.parseString(File(path,"unified-sites.json").readText()).asJsonArray
            .map { Gson().fromJson(it,UnifiedSite::class.java) }
        val sites=CapabilityDetector().apply(UnifiedConfig(sites=input)).sites
        input.filter { it.capability.kind==CapabilityKind.CMS_API }.forEach { original ->
            val updated=sites.first { it.id==original.id }
            assertEquals(CapabilityKind.CMS_API,updated.capability.kind)
            assertEquals(CapabilityStatus.SUPPORTED,updated.capability.status)
        }
        val report=mapOf("total" to sites.size,"capabilities" to sites.groupingBy { it.capability.kind }.eachCount(),
            "classification" to sites.groupingBy { it.capability.status }.eachCount(),"note" to "PARTIAL is a runtime candidate; not proof of working source operations")
        File(System.getProperty("yuno.project.root"),"docs/phase3-reports/capability-snapshot.json")
            .writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
    }
}
