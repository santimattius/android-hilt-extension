package io.github.santimattius.android.hilt.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Maven Central publishing shared by every published module. Coordinates use `libraryGroupId`,
 * the module name as artifact id, and `libraryVersion`. POM fields can be overridden through the
 * `POM_*` properties. Publications are signed unless `-Philtext.skipSigning=true` is passed.
 */
class PublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.vanniktech.maven.publish")
        target.extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            // Signing is on unless a build opts out explicitly. The only opt-out is the compat CI
            // job, which publishes to a throwaway local repository; releases never set it.
            if (target.findProperty("hiltext.skipSigning") != "true") {
                signAllPublications()
            }

            coordinates(
                target.property("libraryGroupId") as String,
                target.name,
                target.property("libraryVersion") as String,
            )

            pom {
                name.set(target.provider { target.name })
                description.set(target.findProperty("POM_DESCRIPTION") as String? ?: "Android Hilt Extension")
                inceptionYear.set(target.findProperty("POM_INCEPTION_YEAR") as String? ?: "2026")
                url.set(target.findProperty("POM_URL") as String? ?: "https://github.com/santimattius/android-hilt-extension/")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set(target.findProperty("POM_DEVELOPER_ID") as String? ?: "santimattius")
                        name.set(target.findProperty("POM_DEVELOPER_NAME") as String? ?: "Santiago Mattiauda")
                        url.set(target.findProperty("POM_DEVELOPER_URL") as String? ?: "https://github.com/santimattius/")
                    }
                }
                scm {
                    url.set(target.findProperty("POM_SCM_URL") as String? ?: "https://github.com/santimattius/android-hilt-extension/")
                    connection.set("scm:git:git://github.com/santimattius/android-hilt-extension.git")
                    developerConnection.set("scm:git:ssh://git@github.com/santimattius/android-hilt-extension.git")
                }
            }
        }
    }
}
