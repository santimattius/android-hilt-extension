package com.santimattius.android.sample.demo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Singleton mailbox through which the injected service reports back to the UI. */
@Singleton
class ServiceReport @Inject constructor() {
    private val latest = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = latest

    fun publish(text: String) {
        latest.value = text
    }
}
