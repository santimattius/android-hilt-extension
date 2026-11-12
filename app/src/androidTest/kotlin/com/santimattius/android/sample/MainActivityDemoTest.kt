package com.santimattius.android.sample

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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

    private fun run(id: DemoId, vararg expected: String) {
        compose.nodeInList(runTag(id)).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            expected.all { text ->
                runCatching {
                    compose.nodeInList(resultTag(id)).assertTextContains(text, substring = true)
                }.isSuccess
            }
        }
        expected.forEach { text ->
            compose.nodeInList(resultTag(id)).assertTextContains(text, substring = true)
        }
    }

    @Test
    fun activityCardRecreatesTheActivityAndShowsItsInjectedGreeting() {
        val initialActivity = compose.activity

        compose.nodeInList(runTag(DemoId.ACTIVITY_INJECTION)).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) { compose.activity !== initialActivity }

        compose.nodeInList(resultTag(DemoId.ACTIVITY_INJECTION))
            .assertTextContains("MainActivity #", substring = true)
            .assertTextContains("injected with GreetingRepository (Perfetto)", substring = true)
    }

    @Test
    fun viewModelCardCreatesAViewModel() = run(
        DemoId.VIEWMODEL_INJECTION,
        "ScratchViewModel #",
        "created with GreetingRepository (Perfetto)",
    )

    @Test
    fun viewCardInflatesAnInjectedView() {
        run(DemoId.VIEW_INJECTION, "Inflated DemoView run #1", "shown below")
        compose.onNodeWithText("DemoView #", substring = true)
            .assertTextContains("injected with GreetingRepository (Perfetto)", substring = true)
    }

    @Test
    fun serviceCardShowsTheServiceInjection() = run(
        DemoId.SERVICE_INJECTION,
        "DemoService #",
        "injected with GreetingRepository (Perfetto)",
    )

    @Test
    fun singletonCardReportsASharedInstance() = run(DemoId.SINGLETON_BINDING, "same instance: true")

    @Test
    fun dispatchersCardReportsEveryQualifier() = run(
        DemoId.DISPATCHERS,
        "@IoDispatcher:",
        "@DefaultDispatcher:",
        "@MainDispatcher:",
        "@ApplicationScope:",
        "(active: true)",
    )

    @Test
    fun suspendProviderCardReportsScopedAndUnscopedBehaviour() = run(
        DemoId.SUSPEND_PROVIDER,
        "scoped: same instance on every get(): true",
        "unscoped: new instance on every get(): true",
        "get() ran on:",
    )

    @Test
    fun suspendLazyCardRunsTheInitializerOnce() = run(
        DemoId.SUSPEND_LAZY,
        "got the same value",
        "the initializer ran 1 time",
    )

    @Test
    fun lazyAwaitCardResolvesOffTheMainThread() = run(
        DemoId.LAZY_AWAIT,
        "constructed on: DefaultDispatcher-worker",
        "second await() returned the same instance: true",
    )

    @Test
    fun traceCoroutineCardReturnsTheBlockResult() = run(
        DemoId.TRACE_COROUTINE,
        "traceCoroutine returned: refreshed on",
        "section: sample.coroutine / refresh (recorded:",
    )

    @Test
    fun blockingResolutionCardShowsTheUninterruptedGet() = run(
        DemoId.BLOCKING_RESOLUTION,
        "caller cancelled while get() was running: true",
        "the running get() still finished: true",
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
