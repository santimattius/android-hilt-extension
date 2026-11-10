package com.santimattius.android.sample.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoCatalogTest {

    @Test
    fun hasExactlyOneSpecPerDemoId() {
        assertEquals(DemoId.entries.toSet(), DemoCatalog.all.map { it.id }.toSet())
        assertEquals(DemoId.entries.size, DemoCatalog.all.size)
    }

    @Test
    fun everySpecHasATitleAndAPromise() {
        assertTrue(DemoCatalog.all.all { it.title.isNotBlank() && it.promise.isNotBlank() })
    }

    @Test
    fun injectionDemosNameTheSectionTheyProduce() {
        val sections = DemoCatalog.all.associate { it.id to it.traceSection }

        assertEquals("hilt.inject MainActivity.members", sections[DemoId.ACTIVITY_INJECTION])
        assertEquals("hilt.inject DemoView.members", sections[DemoId.VIEW_INJECTION])
        assertEquals("hilt.inject DemoService.members", sections[DemoId.SERVICE_INJECTION])
        assertTrue(sections.getValue(DemoId.VIEWMODEL_INJECTION)!!.startsWith("hilt.create "))
        assertEquals("sample.coroutine refresh", sections[DemoId.TRACE_COROUTINE])
    }
}
