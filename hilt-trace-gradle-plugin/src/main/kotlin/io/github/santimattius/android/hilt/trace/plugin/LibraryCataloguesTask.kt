package io.github.santimattius.android.hilt.trace.plugin

import io.github.santimattius.android.hilt.trace.compiler.catalogue.CATALOGUE_DIRECTORY
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

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

    /** Build path of the build the app variant belongs to (`:` for the root build). */
    @get:Internal
    public abstract val currentBuildPath: Property<String>

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
        val owners = mutableMapOf<String, String>()
        val collided = mutableSetOf<String>()
        // Stable order: the library that sorts first keeps the plain catalogue file name on a collision.
        val sorted = artifacts.get().sortedBy { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.let { id -> libraryName(id.build.buildPath, id.projectPath) }.orEmpty() }
        for (artifact in sorted) {
            val id = artifact.id.componentIdentifier as? ProjectComponentIdentifier ?: continue
            val path = id.projectPath
            val buildPath = id.build.buildPath
            val name = libraryName(buildPath, path)
            // A library is read from the project of ITS build: a project of this build, or of a direct included
            // build located by convention (its build's directory plus its path, default `build` directory; its
            // KSP tasks are not visible here). Any other build, such as a build nested in an included build, is
            // not known to this build, and the project with the same path in this build is NOT that library.
            val buildDir = when {
                buildPath == currentBuildPath.get() -> buildDirs.get()[path]?.asFile ?: continue
                buildPath in includedBuilds.get() ->
                    includedBuilds.get().getValue(buildPath).asFile.resolve(path.trim(':').replace(':', '/')).resolve("build")
                else -> {
                    if (warned.add(name)) {
                        logger.warn(
                            "Hilt Trace: library $name is in a build this build cannot locate (a build nested in an included build), " +
                                "so its catalogue is not read and its slices use bytecode names.",
                        )
                    }
                    continue
                }
            }
            val libraryVariant = libraryVariant(artifact.variant.displayName)
            if (libraryVariant == null) {
                // A JVM project (`project ':x'`, or the bare `apiElements`/`runtimeElements`) is not an Android library.
                if (!isAndroidLibraryElements(artifact.variant.displayName)) continue
                if (warned.add(name)) {
                    logger.warn(
                        "Hilt Trace: cannot tell which variant of library $name Gradle selected from '${artifact.variant.displayName}', " +
                            "so its catalogue is not read and its slices use bytecode names.",
                    )
                }
                continue
            }
            // The KSP tasks are known for this build's projects only: an included build's project with the same
            // path as one here must not be judged by that project's tasks.
            val ksp = if (buildPath == currentBuildPath.get()) kspTasks.get()[path].orEmpty() else emptyList()
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
            if (resources.isDirectory) copyResources(resources, out, name, owners, collided)
        }
    }

    /**
     * Copies [resources] into [out]. Catalogue files are named after `hiltTrace.module`, so two libraries that
     * declare the same module id write the same file name: the later one is copied under a distinct name (the
     * loader reads every `*.json`) and reported once, instead of overwriting the first.
     */
    private fun copyResources(
        resources: File,
        out: File,
        library: String,
        owners: MutableMap<String, String>,
        collided: MutableSet<String>,
    ) {
        for (file in resources.walkTopDown().filter { it.isFile }) {
            val relative = file.relativeTo(resources).invariantSeparatorsPath
            var target = out.resolve(relative)
            if (target.exists() && relative.startsWith("$CATALOGUE_DIRECTORY/")) {
                if (target.readBytes().contentEquals(file.readBytes())) continue
                if (collided.add(relative)) {
                    logger.warn(
                        "Hilt Trace: libraries ${owners.getValue(relative)} and $library write the same catalogue file " +
                            "${file.name} (the same hiltTrace.module, or ids that differ only by ':' versus '_'); both are read.",
                    )
                }
                var n = 2
                while (target.resolveSibling("${file.nameWithoutExtension}~$n.${file.extension}").also { target = it }.exists()) n++
            } else {
                owners.putIfAbsent(relative, library)
            }
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }

    internal companion object {
        /**
         * `configuration ':lib:freeDebugRuntimeElements'` -> `freeDebug`; null when the name is not a
         * library elements configuration.
         */
        fun libraryVariant(displayName: String): String? =
            Regex("^([A-Za-z0-9]+?)(?:Api|Runtime)Elements").find(displayName.substringAfterLast(':'))?.groupValues?.get(1)

        /** True for a `configuration ':lib:...'` display name that is not a JVM library's bare elements. */
        fun isAndroidLibraryElements(displayName: String): Boolean =
            displayName.startsWith("configuration ") && displayName.substringAfterLast(':').trimEnd('\'') !in JVM_ELEMENTS

        private val JVM_ELEMENTS = setOf("apiElements", "runtimeElements")

        fun libraryName(buildPath: String, projectPath: String) =
            if (buildPath == ":") projectPath else if (projectPath == ":") buildPath else buildPath + projectPath

        fun kspTaskName(variantName: String) = "ksp${variantName.replaceFirstChar(Char::uppercase)}Kotlin"
    }
}
