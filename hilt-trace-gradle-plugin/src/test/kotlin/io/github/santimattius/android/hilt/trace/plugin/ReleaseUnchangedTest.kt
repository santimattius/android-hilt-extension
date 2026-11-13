package io.github.santimattius.android.hilt.trace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TestKit builds of one throwaway app with and without the plugin: what the release build compiles
 * and dexes must be the same bytes, and AGP must not even have a release transform task. The
 * debug build of the plugin app is the control that shows the plugin is live in the same build.
 */
class ReleaseUnchangedTest {

    @get:Rule
    val withPluginDir = TemporaryFolder()

    @get:Rule
    val pluginFreeDir = TemporaryFolder()

    @Test
    fun `release class and dex bytes equal the plugin-free build and no release transform exists`() {
        val withPlugin = ThrowawayApp(withPluginDir.root, withPlugin = true)
        val output = withPlugin.build("assembleDebug", "assembleRelease", "printTransformTasks")
        val pluginFree = ThrowawayApp(pluginFreeDir.root, withPlugin = false)
        pluginFree.build("assembleRelease")

        val expected = pluginFree.releaseCode()
        // The comparison has content: the component class and a dex are in it.
        assertTrue(expected.keys.toString(), expected.keys.any { it.endsWith("DaggerApp_HiltComponents_SingletonC\$SingletonCImpl.class") })
        assertTrue(expected.keys.toString(), expected.keys.any { it.endsWith(".dex") })
        assertEquals(expected, withPlugin.releaseCode())

        val transformTasks = output.lines().filter { it.startsWith("TASK ") }
        assertTrue(output, "TASK transformDebugClassesWithAsm" in transformTasks)
        assertFalse(output, transformTasks.any { it.contains("Release") })
        // Same build, same fixture class: debug is instrumented, so the equality above is not vacuous.
        assertEquals(listOf("begin", "end", "end"), withPlugin.debugFacadeCalls("consumer"))
    }
}
