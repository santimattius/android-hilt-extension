package io.github.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE

/**
 * Applied to the application module only. Registers [HiltTraceClassVisitorFactory] for the
 * variants selected in `hiltTrace { variants }`, so every other variant (release by default) is
 * not touched and does not package the catalogue files either. `InstrumentationScope.ALL` is needed
 * to reach generated classes of library modules.
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
                } else {
                    // The catalogue is a compile-time input only and lists the app's binding graph: it must not ship.
                    variant.packaging.resources.excludes.add(CATALOGUE_RESOURCES)
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
        return dependencies + ownResources.builtBy(project.tasks.matching { it.name == kspTask }) + libraryResources(project, variant)
    }

    /**
     * The KSP catalogues of the Android library projects on the variant's runtime classpath, collected by
     * [LibraryCataloguesTask] from the library variant Gradle selected for this app variant.
     *
     * The task reads the resolved artifacts when it runs: resolving the runtime classpath while the task
     * graph is built is reported as a resolution at configuration time. It is ordered after the libraries'
     * compilation (hence their KSP task) through the class artifacts it takes as inputs.
     */
    private fun libraryResources(project: Project, variant: ApplicationVariant): FileCollection {
        val view = variant.runtimeConfiguration.incoming.artifactView {
            it.attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
            it.componentFilter { id -> id is ProjectComponentIdentifier }
            it.isLenient = true
        }
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val task = project.tasks.register("hiltTraceLibraryCatalogues$name", LibraryCataloguesTask::class.java) { task ->
            task.artifacts.set(view.artifacts.resolvedArtifacts.map { it.toList() })
            task.libraryClasses.from(view.files)
            task.outputDir.set(project.layout.buildDirectory.dir("intermediates/hilt-trace/libraries/${variant.name}"))
            for (build in project.gradle.includedBuilds) {
                task.includedBuilds.put(":${build.name}", project.layout.projectDirectory.dir(build.projectDir.absolutePath))
            }
            for (other in project.rootProject.allprojects) {
                task.buildDirs.put(other.path, other.layout.buildDirectory)
                task.kspTasks.put(other.path, other.provider { other.tasks.names.filter { it.startsWith("ksp") && it.endsWith("Kotlin") } })
            }
        }
        return project.files(task.flatMap { it.outputDir })
    }

    private companion object {
        const val FAST_INIT_PROPERTY = "dagger.hilt.fastInit"
        const val FAST_INIT_WARNING =
            "Hilt Trace: $FAST_INIT_PROPERTY=false, so Hilt generates no SwitchingProvider; the create rule is skipped."
        const val CATALOGUE_RESOURCES = "META-INF/hilt-trace/**"
        val CATALOGUE_SOURCE_FORMS = listOf("jar", "directory")
    }
}
