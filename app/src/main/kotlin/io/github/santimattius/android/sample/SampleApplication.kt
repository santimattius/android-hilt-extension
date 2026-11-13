package io.github.santimattius.android.sample

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point of the sample; the generated component is what the Hilt Trace plugin instruments. */
@HiltAndroidApp
class SampleApplication : Application()
