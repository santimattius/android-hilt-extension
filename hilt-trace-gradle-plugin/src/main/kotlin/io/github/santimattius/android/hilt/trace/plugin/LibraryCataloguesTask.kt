package io.github.santimattius.android.hilt.trace.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Collects the KSP catalogue of every Android library the app variant resolved to, from the library
 * variant Gradle selected for it, into one directory the instrumentation reads.
 *
 * An Android library does not package its `generated/ksp/<variant>/resources`, so it is read in place. The
 * selected variant comes from the resolved artifacts, which a task reads at execution time: resolving the
 * runtime classpath while the task graph is built is reported by Gradle as a resolution at configuration time.
 * [libraryClasses] orders this task after the libraries' own compilation, which runs their KSP task.
 */
@UntrackedTask(because = "reads the resolved library variants and other projects' build directories, and copies a few small files")
public abstract class LibraryCataloguesTask : DefaultTask() {

    /** The resolved Android library class artifacts of the app variant (project components only). */
    @get:Internal
    public abstract val artifacts: ListProperty<ResolvedArtifactResult>

    /** The same artifacts as files: it carries the task dependencies on the libraries' compilation. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val libraryClasses: ConfigurableFileCollection

    /** Build directory of every project of this build, by project path. */
    @get:Internal
    public abstract val buildDirs: MapProperty<String, Directory>

    /** Root directory of every included build, by build path. */
    @get:Internal
    public abstract val includedBuilds: MapProperty<String, Directory>

    /** The KSP task names of every project of this build, by project path (empty when KSP is not applied). */
    @get:Internal
    public abstract val kspTasks: MapProperty<String, List<String>>

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @TaskAction
    public fun collect() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val warned = mutableSetOf<String>()
        for (artifact in artifacts.get()) {
            val id = artifact.id.componentIdentifier as? ProjectComponentIdentifier ?: continue
            val path = id.projectPath
            // A project of an included build is located by convention: its build's directory plus its path
            // (default `build` directory). It is not known to this build, so its KSP tasks are not checked.
            val included = includedBuilds.get()[id.build.buildPath]?.asFile
            val buildDir = if (included != null) {
                included.resolve(path.trim(':').replace(':', '/')).resolve("build")
            } else {
                buildDirs.get()[path]?.asFile ?: continue
            }
            val libraryVariant = libraryVariant(artifact.variant.displayName) ?: continue
            val ksp = kspTasks.get()[path].orEmpty()
            if (ksp.isNotEmpty() && kspTaskName(libraryVariant) !in ksp) {
                if (warned.add(path)) {
                    logger.warn(
                        "Hilt Trace: library $path has no KSP variant '$libraryVariant' (selected for the app variant), " +
                            "so its catalogue is not read and its slices use bytecode names.",
                    )
                }
                continue
            }
            val resources = buildDir.resolve("generated/ksp/$libraryVariant/resources")
            if (resources.isDirectory) resources.copyRecursively(out, overwrite = true)
        }
    }

    internal companion object {
        /**
         * `configuration ':lib:freeDebugRuntimeElements'` -> `freeDebug`; null when the name is not a
         * library elements configuration.
         */
        fun libraryVariant(displayName: String): String? =
            Regex("^([A-Za-z0-9]+?)(?:Api|Runtime)Elements").find(displayName.substringAfterLast(':'))?.groupValues?.get(1)

        fun kspTaskName(variantName: String) = "ksp${variantName.replaceFirstChar(Char::uppercase)}Kotlin"
    }
}
