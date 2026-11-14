package io.github.santimattius.android.hilt.trace.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import java.util.concurrent.Callable

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
     * The KSP resources directory of every Android library project on the variant's runtime classpath.
     *
     * KSP writes the catalogue to `generated/ksp/<variant>/resources`, but an Android library does not
     * package that directory: the catalogue is in none of the library's variants (`android-classes-jar`,
     * `android-java-res`, `jar`), so [CatalogueTransform] never sees it. The directory is read in place and
     * the library's `ksp<Variant>Kotlin` task is declared as its producer.
     *
     * The libraries are found from the declared project dependencies ([androidLibraries]), not by resolving
     * the runtime classpath: Gradle visits the producers of these files while it builds the task graph, and
     * resolving a configuration there is reported by AGP as a resolution at configuration time. The library
     * variant matches the app variant by name: a library that applies KSP but has no such variant is
     * skipped with one warning (fail open).
     */
    private fun libraryResources(project: Project, variant: ApplicationVariant): FileCollection {
        val kspTask = "ksp${variant.name.replaceFirstChar(Char::uppercase)}Kotlin"
        val warned = mutableSetOf<String>()
        val libraries = {
            androidLibraries(project, variant).filter { library ->
                (kspTask in library.tasks.names).also { hasVariant ->
                    if (!hasVariant && library.plugins.hasPlugin(KSP_PLUGIN) && warned.add(library.path)) {
                        project.logger.warn(
                            "Hilt Trace: library ${library.path} has no KSP variant '${variant.name}', " +
                                "so its catalogue is not read and its slices use bytecode names.",
                        )
                    }
                }
            }
        }
        return project.files(Callable { libraries().map { it.layout.buildDirectory.dir("generated/ksp/${variant.name}/resources") } })
            .builtBy(Callable { libraries().map { library -> library.tasks.matching { it.name == kspTask } } })
    }

    /**
     * The Android library projects reachable from the variant through declared project dependencies, in
     * the current build (a project dependency never names an included build). Fail open: an error while
     * reading the declarations gives the libraries found so far and one warning.
     */
    private fun androidLibraries(project: Project, variant: ApplicationVariant): List<Project> {
        val found = linkedMapOf<String, Project>()
        try {
            val pending = ArrayDeque(projectPaths(variant.runtimeConfiguration))
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                val library = project.rootProject.findProject(path)?.takeIf { it.plugins.hasPlugin("com.android.library") }
                if (library == null || path in found) continue
                found[path] = library
                library.configurations.findByName("${variant.name}RuntimeClasspath")?.let { pending.addAll(projectPaths(it)) }
            }
        } catch (e: Exception) {
            project.logger.warn("Hilt Trace: the project dependencies of '${variant.name}' could not be read, so some Android library catalogues are skipped: ${e.message}")
        }
        return found.values.toList()
    }

    private fun projectPaths(configuration: Configuration): List<String> =
        configuration.allDependencies.withType(ProjectDependency::class.java).map { it.path }

    private companion object {
        const val FAST_INIT_PROPERTY = "dagger.hilt.fastInit"
        const val FAST_INIT_WARNING =
            "Hilt Trace: $FAST_INIT_PROPERTY=false, so Hilt generates no SwitchingProvider; the create rule is skipped."
        const val KSP_PLUGIN = "com.google.devtools.ksp"
        const val CATALOGUE_RESOURCES = "META-INF/hilt-trace/**"
        val CATALOGUE_SOURCE_FORMS = listOf("jar", "directory")
    }
}
