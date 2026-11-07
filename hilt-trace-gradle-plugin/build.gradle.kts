plugins {
    // The Kotlin plugin version comes from the catalog; this build has no AGP to supply it.
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("hiltext.jvm-library")
    id("hiltext.publishing")
}

kotlin {
    explicitApi()
    // The catalogue model and merger are shared with :hilt-trace-compiler by source (they have no KSP
    // imports), so no extra artifact is published and their tests stay in :hilt-trace-compiler.
    sourceSets.main {
        kotlin.srcDir("../hilt-trace-compiler/src/main/kotlin/com/santimattius/android/hilt/trace/compiler/catalogue")
    }
}

// AGP resolved with runtime attributes (the compile classpath only has API variants) for TestKit.
val testPlugins = configurations.create("testPlugins") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

// The coordinates of the artifacts the plugin adds to apps follow the build's own library version.
tasks.processResources {
    // Local value: the filter closure must not reference the script object (configuration cache).
    val coordinates = mapOf(
        "libraryGroupId" to project.property("libraryGroupId") as String,
        "libraryVersion" to project.property("libraryVersion") as String,
    )
    inputs.properties(coordinates)
    filesMatching("hilt-trace-plugin.properties") { expand(coordinates) }
}

dependencies {
    // AGP and ASM are supplied by the consumer build at runtime; never bundled into the plugin.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.asm)
    compileOnly(libs.asm.tree)

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
    testPlugins(libs.android.gradlePlugin)
}

// TestKit applies the plugin under test plus AGP for the fixture Android app.
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPlugins)
}

gradlePlugin {
    plugins {
        register("hiltTrace") {
            id = "com.santimattius.android.hilt.trace"
            implementationClass = "com.santimattius.android.hilt.trace.plugin.HiltTracePlugin"
            displayName = "Hilt trace instrumentation"
            description = "Adds Perfetto trace slices around Hilt object creation and injection in debug variants."
        }
    }
}
