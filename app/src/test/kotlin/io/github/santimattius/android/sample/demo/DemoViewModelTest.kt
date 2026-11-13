@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.santimattius.android.sample.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DemoViewModelTest {

    private val report = ServiceReport()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(vararg demos: Pair<DemoId, SuspendDemo>) =
        DemoViewModel(DemoRegistry(mapOf(*demos)), report)

    private fun DemoViewModel.card(id: DemoId) = cards.value.single { it.spec.id == id }

    @Test
    fun startsWithOneIdleCardPerCatalogueEntry() {
        val vm = viewModel()

        assertEquals(DemoCatalog.all, vm.cards.value.map { it.spec })
        assertTrue(vm.cards.value.all { it.result == null && !it.running })
    }

    @Test
    fun runShowsTheResultOfTheDemo() {
        val vm = viewModel(DemoId.SUSPEND_LAZY to SuspendDemo { "ok" })

        vm.run(DemoId.SUSPEND_LAZY)

        assertEquals("ok", vm.card(DemoId.SUSPEND_LAZY).result)
        assertFalse(vm.card(DemoId.SUSPEND_LAZY).running)
    }

    @Test
    fun runMarksTheCardRunningUntilTheDemoFinishes() {
        val gate = CompletableDeferred<String>()
        val vm = viewModel(DemoId.LAZY_AWAIT to SuspendDemo { gate.await() })

        vm.run(DemoId.LAZY_AWAIT)
        assertTrue(vm.card(DemoId.LAZY_AWAIT).running)

        gate.complete("done")
        assertFalse(vm.card(DemoId.LAZY_AWAIT).running)
        assertEquals("done", vm.card(DemoId.LAZY_AWAIT).result)
    }

    @Test
    fun aFailingDemoShowsTheErrorInsteadOfCrashing() {
        val vm = viewModel(DemoId.DISPATCHERS to SuspendDemo { error("boom") })

        vm.run(DemoId.DISPATCHERS)

        assertEquals("Failed: IllegalStateException: boom", vm.card(DemoId.DISPATCHERS).result)
        assertFalse(vm.card(DemoId.DISPATCHERS).running)
    }

    @Test
    fun runningADemoWithoutAnImplementationLeavesTheCardUntouched() {
        val vm = viewModel()

        vm.run(DemoId.TRACE_COROUTINE)

        assertNull(vm.card(DemoId.TRACE_COROUTINE).result)
    }

    @Test
    fun reportSetsTheResultOfAnAndroidBoundCard() {
        val vm = viewModel()

        vm.report(DemoId.ACTIVITY_INJECTION, "injected")

        assertEquals("injected", vm.card(DemoId.ACTIVITY_INJECTION).result)
    }

    @Test
    fun theServiceReportReachesTheServiceCard() {
        val vm = viewModel()

        report.publish("service injected")

        assertEquals("service injected", vm.card(DemoId.SERVICE_INJECTION).result)
    }
}
