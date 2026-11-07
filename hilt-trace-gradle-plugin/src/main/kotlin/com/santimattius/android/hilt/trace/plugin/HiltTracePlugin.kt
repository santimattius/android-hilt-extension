package com.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied to the application module only. Registers [HiltTraceClassVisitorFactory] for the
 * variants selected in `hiltTrace { variants }`, so every other variant (release by default) is
 * not touched. `InstrumentationScope.ALL` is needed to reach generated classes of library modules.
 */
public class HiltTracePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hiltTrace", HiltTraceExtension::class.java)
        extension.applyDefaults()

        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                if (extension.enabled.get() && variant.name in extension.variants.get()) {
                    variant.instrumentation.transformClassesWith(
                        HiltTraceClassVisitorFactory::class.java,
                        InstrumentationScope.ALL,
                    ) {}
                }
            }
        }
    }
}
