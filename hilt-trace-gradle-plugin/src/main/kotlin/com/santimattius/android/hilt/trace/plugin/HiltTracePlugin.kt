package com.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
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

        // KSP is mandatory for the catalogue; the compiler arrives with the plugin at its own version.
        project.configurations.matching { it.name == "ksp" }.configureEach {
            it.dependencies.add(project.dependencies.create(PluginCoordinates.compiler))
        }

        project.pluginManager.withPlugin("com.android.application") {
            project.dependencies.add("implementation", PluginCoordinates.runtime)
            val fastInit = project.providers.gradleProperty(FAST_INIT_PROPERTY).map { !it.equals("false", ignoreCase = true) }.orElse(true)
            var warned = false
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                if (extension.enabled.get() && variant.name in extension.variants.get()) {
                    if (!fastInit.get() && !warned) {
                        warned = true
                        project.logger.warn(FAST_INIT_WARNING)
                    }
                    variant.instrumentation.transformClassesWith(
                        HiltTraceClassVisitorFactory::class.java,
                        InstrumentationScope.ALL,
                    ) { parameters ->
                        parameters.createRuleEnabled.set(fastInit)
                        parameters.catalogues.from(catalogueRoots(project, variant))
                    }
                    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
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

    /**
     * The catalogue roots of one variant: its dependencies reduced by [CatalogueTransform] (lenient, so
     * an entry that has no jar or classes form is skipped) and the app's own KSP resources directory,
     * which must exist only after the variant's KSP task has run.
     */
    private fun catalogueRoots(project: Project, variant: ApplicationVariant): FileCollection {
        val dependencies = variant.runtimeConfiguration.incoming.artifactView {
            it.attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, CATALOGUE_ARTIFACT_TYPE)
            it.isLenient = true
        }.files
        val kspTask = "ksp${variant.name.replaceFirstChar(Char::uppercase)}Kotlin"
        val ownResources = project.files(project.layout.buildDirectory.dir("generated/ksp/${variant.name}/resources"))
        return dependencies + ownResources.builtBy(project.tasks.matching { it.name == kspTask })
    }

    private companion object {
        const val FAST_INIT_PROPERTY = "dagger.hilt.fastInit"
        const val FAST_INIT_WARNING =
            "Hilt Trace: $FAST_INIT_PROPERTY=false, so Hilt generates no SwitchingProvider; the create rule is skipped."
        val CATALOGUE_SOURCE_FORMS = listOf("jar", "directory")
    }
}
