package com.santimattius.android.sample.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.AttributeSet
import android.widget.TextView
import androidx.lifecycle.ViewModel
import com.santimattius.android.sample.demo.ServiceReport
import com.santimattius.android.sample.greeting.GreetingRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** ViewModel created on demand, once per Run, to show that Hilt creation is traced. */
@HiltViewModel
class ScratchViewModel @Inject constructor(
    greetings: GreetingRepository,
) : ViewModel() {
    val description: String = "ScratchViewModel #${System.identityHashCode(this)} " +
        "created with GreetingRepository (${greetings.audienceName()})"
}

/** View with field injection: Hilt injects it from its constructor, traced as `DemoView.members`. */
@AndroidEntryPoint
class DemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    @Inject
    lateinit var greetings: GreetingRepository

    init {
        text = "DemoView #${System.identityHashCode(this)} injected with GreetingRepository " +
            "(${greetings.audienceName()})"
    }
}

/** Service with field injection, traced as `DemoService.members`; it reports back and stops itself. */
@AndroidEntryPoint
class DemoService : Service() {

    @Inject
    lateinit var report: ServiceReport

    @Inject
    lateinit var greetings: GreetingRepository

    override fun onCreate() {
        super.onCreate()
        report.publish(
            "DemoService #${System.identityHashCode(this)} injected with GreetingRepository " +
                "(${greetings.audienceName()})",
        )
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
