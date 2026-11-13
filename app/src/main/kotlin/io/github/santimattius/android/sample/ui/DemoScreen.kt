package io.github.santimattius.android.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.santimattius.android.sample.demo.DemoCardState
import io.github.santimattius.android.sample.demo.DemoId

/** Test tag of the list that holds the cards. */
const val DEMO_LIST_TAG = "demo-list"

/** Test tag of the Run button of [id]. */
fun runTag(id: DemoId) = "run-${id.name}"

/** Test tag of the result text of [id]. */
fun resultTag(id: DemoId) = "result-${id.name}"

/**
 * Stateless list of demo cards. [extraContent] lets the host add content under a card, which the
 * sample uses to embed the injected view; it defaults to nothing so the screen renders without Hilt.
 */
@Composable
fun DemoScreen(
    cards: List<DemoCardState>,
    onRun: (DemoId) -> Unit,
    modifier: Modifier = Modifier,
    extraContent: @Composable (DemoId) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.testTag(DEMO_LIST_TAG),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Hilt Trace sample", style = MaterialTheme.typography.headlineSmall) }
        items(cards, key = { it.spec.id }) { card ->
            DemoCard(card, onRun = { onRun(card.spec.id) }, extraContent = { extraContent(card.spec.id) })
        }
    }
}

@Composable
fun DemoCard(
    card: DemoCardState,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    extraContent: @Composable () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.spec.title, style = MaterialTheme.typography.titleMedium)
            Text(card.spec.promise, style = MaterialTheme.typography.bodyMedium)
            card.spec.traceSection?.let {
                Text("Trace section: $it", style = MaterialTheme.typography.labelSmall)
            }
            Row {
                Button(
                    onClick = onRun,
                    enabled = !card.running,
                    modifier = Modifier.testTag(runTag(card.spec.id)),
                ) {
                    Text(if (card.running) "Running..." else "Run")
                }
            }
            Text(
                text = card.result ?: "No result yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(resultTag(card.spec.id)),
            )
            extraContent()
        }
    }
}
