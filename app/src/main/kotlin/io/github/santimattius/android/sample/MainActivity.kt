package io.github.santimattius.android.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.santimattius.android.sample.android.DemoService
import io.github.santimattius.android.sample.android.DemoView
import io.github.santimattius.android.sample.android.ScratchViewModel
import io.github.santimattius.android.sample.demo.DemoId
import io.github.santimattius.android.sample.demo.DemoViewModel
import io.github.santimattius.android.sample.greeting.GreetingRepository
import io.github.santimattius.android.sample.ui.DemoScreen
import io.github.santimattius.android.sample.ui.theme.SampleTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Field injection: Hilt generates injectMainActivity, which the plugin traces as hilt.inject.
    @Inject
    lateinit var greetings: GreetingRepository

    private val demos: DemoViewModel by viewModels()

    private var viewRuns by mutableIntStateOf(0)
    private var scratchRuns = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        demos.report(
            DemoId.ACTIVITY_INJECTION,
            "MainActivity #${System.identityHashCode(this)} injected with GreetingRepository " +
                "(${greetings.audienceName()})",
        )
        setContent {
            SampleTheme {
                val cards by demos.cards.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoScreen(
                        cards = cards,
                        onRun = ::runDemo,
                        modifier = Modifier.padding(innerPadding),
                        extraContent = { id ->
                            if (id == DemoId.VIEW_INJECTION && viewRuns > 0) {
                                androidx.compose.runtime.key(viewRuns) {
                                    AndroidView(factory = { DemoView(it) })
                                }
                            }
                            if (id == DemoId.COMPLEX_GRAPH_MAIN_THREAD) {
                                // Always spinning; it freezes while the main thread is blocked.
                                Text("Live animation (freezes while the main thread is blocked):")
                                CircularProgressIndicator()
                            }
                        },
                    )
                }
            }
        }
    }

    /** Runs the action of a card; also called by the instrumented tests. */
    fun runDemo(id: DemoId) {
        when (id) {
            DemoId.ACTIVITY_INJECTION -> recreate()
            DemoId.VIEWMODEL_INJECTION -> demos.report(id, createScratchViewModel().description)
            DemoId.VIEW_INJECTION -> {
                viewRuns++
                demos.report(id, "Inflated DemoView run #$viewRuns, shown below")
            }
            DemoId.SERVICE_INJECTION -> startService(Intent(this, DemoService::class.java))
            else -> demos.run(id)
        }
    }

    /** A new key per run gives a new instance, so each Run creates (and traces) a ViewModel. */
    private fun createScratchViewModel(): ScratchViewModel {
        scratchRuns++
        return ViewModelProvider(this)["scratch-$scratchRuns", ScratchViewModel::class.java]
    }
}
