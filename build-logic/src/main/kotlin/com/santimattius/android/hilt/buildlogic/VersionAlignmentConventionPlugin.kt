package com.santimattius.android.hilt.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers `verifyVersionAlignment` and makes `check` depend on it. The root build lists the
 * `gradle.properties` files to compare through `propertyFiles`.
 */
class VersionAlignmentConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("base")
        val verify = target.tasks.register("verifyVersionAlignment", VerifyVersionAlignment::class.java) {
            baseDirectory.set(target.layout.projectDirectory)
            group = "verification"
            description = "Fails when the libraryVersion defaults of the root and included builds drift."
        }
        target.tasks.named("check") { dependsOn(verify) }
    }
}
