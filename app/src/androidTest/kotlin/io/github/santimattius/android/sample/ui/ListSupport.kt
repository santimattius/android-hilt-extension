package io.github.santimattius.android.sample.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** The card list is lazy, so a node only exists once the list has been scrolled to it. */
fun ComposeTestRule.nodeInList(tag: String): SemanticsNodeInteraction {
    onNodeWithTag(DEMO_LIST_TAG).performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}
