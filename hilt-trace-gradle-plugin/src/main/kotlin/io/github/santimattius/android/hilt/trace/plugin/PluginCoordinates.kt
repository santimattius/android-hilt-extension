package io.github.santimattius.android.hilt.trace.plugin

import java.util.Properties

/**
 * Coordinates of the artifacts the plugin adds to the consuming app. The group and version come from
 * `hilt-trace-plugin.properties`, which the build fills from `libraryGroupId` and `libraryVersion`, so
 * the plugin and the artifacts it depends on are always released with one version.
 */
internal object PluginCoordinates {

    private val properties = Properties().apply {
        val stream = PluginCoordinates::class.java.getResourceAsStream("/hilt-trace-plugin.properties")
        requireNotNull(stream) { "hilt-trace-plugin.properties is missing from the plugin jar" }.use(::load)
    }

    val compiler: String get() = coordinates("hilt-trace-compiler")

    val runtime: String get() = coordinates("hilt-trace-runtime")

    private fun coordinates(artifactId: String) = "${properties.getProperty("group")}:$artifactId:${properties.getProperty("version")}"
}
