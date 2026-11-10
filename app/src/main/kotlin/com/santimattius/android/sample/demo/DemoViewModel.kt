package com.santimattius.android.sample.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DemoCardState(
    val spec: DemoSpec,
    val result: String? = null,
    val running: Boolean = false,
)

/** Holds the state of every card; the composables only render it. */
@HiltViewModel
class DemoViewModel @Inject constructor(
    private val registry: DemoRegistry,
    serviceReport: ServiceReport,
) : ViewModel() {

    private val state = MutableStateFlow(DemoCatalog.all.map { DemoCardState(it) })
    val cards: StateFlow<List<DemoCardState>> = state

    init {
        viewModelScope.launch {
            serviceReport.message.collect { text ->
                if (text != null) report(DemoId.SERVICE_INJECTION, text)
            }
        }
    }

    /** Runs the coroutine-based demo of [id]; ignored when the card has no such demo. */
    fun run(id: DemoId) {
        val demo = registry.find(id) ?: return
        update(id) { it.copy(running = true) }
        viewModelScope.launch {
            val text = try {
                demo.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            update(id) { it.copy(running = false, result = text) }
        }
    }

    /** Sets the result of a card whose action runs in an Android component. */
    fun report(id: DemoId, text: String) = update(id) { it.copy(result = text) }

    private fun update(id: DemoId, change: (DemoCardState) -> DemoCardState) =
        state.update { cards -> cards.map { if (it.spec.id == id) change(it) else it } }
}
