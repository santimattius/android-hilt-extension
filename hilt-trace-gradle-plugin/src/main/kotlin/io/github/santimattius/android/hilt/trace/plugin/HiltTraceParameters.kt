package io.github.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/** Inputs of [HiltTraceClassVisitorFactory]; being task inputs they keep the transform up to date and cacheable. */
public interface HiltTraceParameters : InstrumentationParameters {

    /**
     * Roots holding `META-INF/hilt-trace/<name>.json`: the extracted catalogues of the variant's
     * dependencies plus the app's own KSP resources directory. A root may not exist.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public val catalogues: ConfigurableFileCollection

    /** False when Hilt fast init is disabled: no `SwitchingProvider` is generated, so the create rule has nothing to wrap. */
    @get:Input
    public val createRuleEnabled: Property<Boolean>
}
