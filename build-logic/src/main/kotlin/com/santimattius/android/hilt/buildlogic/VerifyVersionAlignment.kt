package com.santimattius.android.hilt.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

/**
 * Fails when the `libraryVersion` defaults of the given `gradle.properties` files differ.
 * An included build does not read the root `gradle.properties`, so each build keeps its own
 * default and this task is the only guard against drift.
 */
abstract class VerifyVersionAlignment : DefaultTask() {

    /** Properties files to compare. The first file is the reference (the root build). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val propertyFiles: ConfigurableFileCollection

    /** Base directory used only to print repository-relative paths in messages. */
    @get:Internal
    abstract val baseDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val versions = propertyFiles.files.map { it to readVersion(it) }
        val reference = versions.firstOrNull() ?: return
        val drifting = versions.filter { it.second != reference.second }
        if (drifting.isNotEmpty()) {
            val details = drifting.joinToString("\n") { (file, version) -> "  ${label(file)}: $version" }
            throw GradleException(
                "libraryVersion defaults are not aligned. Reference ${label(reference.first)}: " +
                    "${reference.second}\n$details",
            )
        }
    }

    private fun readVersion(file: File): String {
        val properties = Properties().apply { file.inputStream().use(::load) }
        return properties.getProperty("libraryVersion")
            ?: throw GradleException("${label(file)} does not declare libraryVersion")
    }

    private fun label(file: File): String = file.relativeToOrSelf(baseDirectory.get().asFile).invariantSeparatorsPath
}
