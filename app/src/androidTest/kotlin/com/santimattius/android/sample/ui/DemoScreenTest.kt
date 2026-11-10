package com.santimattius.android.sample.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.santimattius.android.sample.demo.DemoCardState
import com.santimattius.android.sample.demo.DemoCatalog
import com.santimattius.android.sample.demo.DemoId
import com.santimattius.android.sample.ui.theme.SampleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the stateless screen without Hilt: it only needs state in and a click callback out. */
@RunWith(AndroidJUnit4::class)
class DemoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(cards: List<DemoCardState>, onRun: (DemoId) -> Unit = {}) {
        compose.setContent { SampleTheme { DemoScreen(cards, onRun) } }
    }

    @Test
    fun cardWithoutAResultInvitesToRun() {
        show(DemoCatalog.all.map { DemoCardState(it) })

        compose.nodeInList(resultTag(DemoId.SUSPEND_LAZY)).assertTextEquals("No result yet")
        compose.nodeInList(runTag(DemoId.SUSPEND_LAZY)).assertIsEnabled()
    }

    @Test
    fun clickingRunReportsTheCardId() {
        val clicked = mutableListOf<DemoId>()
        show(DemoCatalog.all.map { DemoCardState(it) }, onRun = { clicked += it })

        compose.nodeInList(runTag(DemoId.LAZY_AWAIT)).performClick()

        assertEquals(listOf(DemoId.LAZY_AWAIT), clicked)
    }

    @Test
    fun resultIsShownAndARunningCardDisablesItsButton() {
        val cards = DemoCatalog.all.map {
            when (it.id) {
                DemoId.DISPATCHERS -> DemoCardState(it, result = "io: worker")
                DemoId.TRACE_COROUTINE -> DemoCardState(it, running = true)
                else -> DemoCardState(it)
            }
        }
        show(cards)

        compose.nodeInList(resultTag(DemoId.DISPATCHERS)).assertTextEquals("io: worker")
        compose.nodeInList(runTag(DemoId.TRACE_COROUTINE)).assertIsNotEnabled()
    }
}
