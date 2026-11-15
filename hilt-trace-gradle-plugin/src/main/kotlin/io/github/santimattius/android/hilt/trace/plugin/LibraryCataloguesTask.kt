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
            // build located by convention (its build's directory plus its path, default `build` directory, verified against the
            // resolved artifact's own location; its KSP tasks are not visible here). Any other build, such as a build nested in an included build, is
            // not known to this build, and the project with the same path in this build is NOT that library.
            val buildDir = when {
                buildPath == currentBuildPath.get() -> buildDirs.get()[path]?.asFile ?: continue
                buildPath in includedBuilds.get() -> {
                    val conventional = includedBuilds.get().getValue(buildPath).asFile.resolve(path.trim(':').replace(':', '/')).resolve("build")
                    // The artifact is a file of the library's real build directory: if it is not under the
                    // conventional one, the project or its build directory was relocated, and what sits at
                    // the conventional place is not this library's output.
                    if (!artifact.file.canonicalFile.startsWith(conventional.canonicalFile)) {
                        if (warned.add(name)) {
                            logger.warn(
                                "Hilt Trace: library $name is in an included build whose project or build directory is not at the " +
                                    "conventional place, so its catalogue is not read and its slices use bytecode names.",
                            )
                        }
                        continue
                    }
                    conventional
                }
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
            val attributes = artifact.variant.attributes.let { all -> all.keySet().associate { it.name to all.getAttribute(it).toString() } }
            val libraryVariant = libraryVariant(attributes)
            if (libraryVariant == null) {
                // A JVM project has none of the Android variant attributes: it is not an Android library.
                if (!isAndroidLibrary(attributes)) continue
                if (warned.add(name)) {
                    logger.warn(
                        "Hilt Trace: cannot tell which variant of library $name Gradle selected from its attributes $attributes, " +
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
        private const val BUILD_TYPE = "com.android.build.api.attributes.BuildTypeAttr"
        private const val FLAVOR_PREFIX = "com.android.build.api.attributes.ProductFlavor:"
        private const val VARIANT = "com.android.build.gradle.internal.attributes.VariantAttr"

        /** True when the resolved variant carries the Android build type attribute; a JVM library does not. */
        fun isAndroidLibrary(attributes: Map<String, String>): Boolean = BUILD_TYPE in attributes

        /**
         * The library variant Gradle selected, from the attributes of the resolved variant: AGP's variant name
         * attribute (`freeDebug`), or the build type of a library without flavors. Null when it cannot be told.
         */
        fun libraryVariant(attributes: Map<String, String>): String? =
            attributes[VARIANT] ?: attributes[BUILD_TYPE]?.takeIf { attributes.keys.none { it.startsWith(FLAVOR_PREFIX) } }

        fun libraryName(buildPath: String, projectPath: String) =
            if (buildPath == ":") projectPath else if (projectPath == ":") buildPath else buildPath + projectPath

        fun kspTaskName(variantName: String) = "ksp${variantName.replaceFirstChar(Char::uppercase)}Kotlin"
    }
}
