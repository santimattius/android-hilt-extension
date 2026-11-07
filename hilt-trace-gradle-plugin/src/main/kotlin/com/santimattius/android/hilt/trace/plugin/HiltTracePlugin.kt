package com.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE

/**
 * Applied to the application module only. Registers [HiltTraceClassVisitorFactory] for the
 * variants selected in `hiltTrace { variants }`, so every other variant (release by default) is
 * not touched. `InstrumentationScope.ALL` is needed to reach generated classes of library modules.
 */
public class HiltTracePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("hiltTrace", HiltTraceExtension::class.java)
        extension.applyDefaults()
        registerCatalogueTransform(project)

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

    /** Registers [CatalogueTransform] for the classpath entry forms a catalogue can arrive in. */
    private fun registerCatalogueTransform(project: Project) {
        for (form in CATALOGUE_SOURCE_FORMS) {
            project.dependencies.registerTransform(CatalogueTransform::class.java) { spec ->
                spec.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, form)
                spec.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, CATALOGUE_ARTIFACT_TYPE)
            }
        }
    }

    private companion object {
        val CATALOGUE_SOURCE_FORMS = listOf("jar", "directory")
    }
}
