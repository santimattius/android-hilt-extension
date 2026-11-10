package com.santimattius.android.sample

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.santimattius.android.sample.demo.DemoId
import com.santimattius.android.sample.ui.nodeInList
import com.santimattius.android.sample.ui.resultTag
import com.santimattius.android.sample.ui.runTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real activity with its real Hilt graph and checks the visible result of each card. */
@RunWith(AndroidJUnit4::class)
class MainActivityDemoTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun run(id: DemoId, expected: String) {
        compose.nodeInList(runTag(id)).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            runCatching { compose.nodeInList(resultTag(id)).assertTextContains(expected, substring = true) }.isSuccess
        }
    }

    @Test
    fun activityCardShowsTheInjectedGreeting() {
        compose.nodeInList(resultTag(DemoId.ACTIVITY_INJECTION))
            .assertTextContains("injected with GreetingRepository (Perfetto)", substring = true)
    }

    @Test
    fun viewModelCardCreatesAViewModel() = run(DemoId.VIEWMODEL_INJECTION, "ScratchViewModel")

    @Test
    fun viewCardInflatesAnInjectedView() = run(DemoId.VIEW_INJECTION, "Inflated DemoView")

    @Test
    fun serviceCardShowsTheServiceInjection() = run(DemoId.SERVICE_INJECTION, "DemoService")

    @Test
    fun singletonCardReportsASharedInstance() = run(DemoId.SINGLETON_BINDING, "same instance: true")

    @Test
    fun dispatchersCardReportsEveryQualifier() = run(DemoId.DISPATCHERS, "@ApplicationScope")

    @Test
    fun suspendProviderCardReportsScopedAndUnscopedBehaviour() =
        run(DemoId.SUSPEND_PROVIDER, "unscoped: new instance on every get(): true")

    @Test
    fun suspendLazyCardRunsTheInitializerOnce() = run(DemoId.SUSPEND_LAZY, "ran 1 time")

    @Test
    fun lazyAwaitCardResolvesOffTheMainThread() = run(DemoId.LAZY_AWAIT, "same instance: true")

    @Test
    fun traceCoroutineCardReturnsTheBlockResult() = run(DemoId.TRACE_COROUTINE, "traceCoroutine returned: refreshed on")

    @Test
    fun blockingResolutionCardShowsTheUninterruptedGet() =
        run(DemoId.BLOCKING_RESOLUTION, "the running get() still finished: true")

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
