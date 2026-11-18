package io.github.santimattius.android.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import io.github.santimattius.android.hilt.trace.HiltTrace

/** Hilt entry point of the sample; the generated component is what the Hilt Trace plugin instruments. */
@HiltAndroidApp
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Opt in to the system-wide Perfetto mirror, only for debuggable builds.
        HiltTrace.setSystemTraceEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }
}
