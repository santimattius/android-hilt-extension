plugins {
    `kotlin-dsl`
}

group = "io.github.santimattius.android.hilt.buildlogic"

val testPlugins = configurations.create("testPlugins") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

// AGP and Kotlin are compileOnly so the root build supplies a single copy on its classpath;
// the Maven publish plugin is only used here, so it ships with the convention plugins.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.maven.publish.gradlePlugin)

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
    testPlugins(libs.android.gradlePlugin)
    testPlugins(libs.kotlin.gradlePlugin)
}

// TestKit needs AGP and Kotlin on the plugin classpath because main only declares them compileOnly.
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPlugins)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "hiltext.android-library"
            implementationClass = "io.github.santimattius.android.hilt.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "hiltext.jvm-library"
            implementationClass = "io.github.santimattius.android.hilt.buildlogic.JvmLibraryConventionPlugin"
        }
        register("versionAlignment") {
            id = "hiltext.version-alignment"
            implementationClass = "io.github.santimattius.android.hilt.buildlogic.VersionAlignmentConventionPlugin"
        }
        register("publishing") {
            id = "hiltext.publishing"
            implementationClass = "io.github.santimattius.android.hilt.buildlogic.PublishingConventionPlugin"
        }
    }
}
