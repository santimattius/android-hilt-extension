import com.android.build.api.variant.HasUnitTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("io.github.santimattius.android.hilt.trace")
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project
val libraryGroupId: String by project
val sampleJitPackVersion = providers.gradleProperty("sampleJitPackVersion").orNull
val jitPackGroup = "com.github.santimattius.android-hilt-extension"

// Canary switch (never set it in normal builds): -PdisableComposePreviewWorkaround=resources|unit-tests
// drops one of the two settings below so scripts/check-compose-preview-canary.sh can prove each is still
// needed.
val dropPreviewWorkaround = providers.gradleProperty("disableComposePreviewWorkaround").orNull
val keepPreviewResources = dropPreviewWorkaround != "resources"
val keepPreviewUnitTests = dropPreviewWorkaround != "unit-tests"

android {
    namespace = "io.github.santimattius.android.sample"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        applicationId = "io.github.santimattius.android.sample"
        minSdk = androidMinSdkVersion.toInt()
        targetSdk = androidTargetSdkVersion.toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Which build type the instrumented tests run against. Default: debug. Pass
    // -PtestBuildType=minified to run them against the R8-minified, instrumented build type instead (see
    // :instrumentedTestsMinifiedCi and docs/VERIFY.md). AGP allows one test build type per build, so the
    // switch is a property; the managed-device task is :app:ci<Debug|Minified>AndroidTest accordingly.
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

    buildTypes {
        release {
            // Opt-in only (-PminifyRelease=true, see :app:verifyMinifiedReleaseUntraced); the default release
            // stays unminified and uninstrumented.
            isMinifyEnabled = providers.gradleProperty("minifyRelease").orNull == "true"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug-like and instrumented (see hiltTrace { variants }) but shrunk and obfuscated by R8, so
        // :app:verifyMinifiedTracing can prove tracing survives minification. Release stays unminified
        // and uninstrumented.
        create("minified") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isDebuggable = false
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // R8 runs separately over the app and over the androidTest APK, and neither sees the tests'
            // references to the app: the keep rules go to the app run only when the tests target this build
            // type (-PtestBuildType=minified), so verifyMinifiedTracing and normal builds are unchanged. The
            // test APK run gets the same file through testProguardFiles (it needs the dontwarn).
            if (testBuildType == "minified") proguardFiles("proguard-test-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Workaround (AGP 9.5.0 with Kotlin 2.4.21 Compose plugin): AGP creates a Compose Preview runfiles
        // task for every variant when the Compose compiler plugin is applied, and it fails while the
        // bytecode transform task resolves its dependencies unless Android resources and unit tests are
        // enabled for the variant. The failure is independent of the Hilt Trace plugin.
        if (keepPreviewResources) unitTests.isIncludeAndroidResources = true
        // androidx.tracing reads android.os.Process while initializing; the demo unit tests call it.
        unitTests.isReturnDefaultValues = true
        // Gradle Managed Device for CI: AGP downloads the system image, boots it headless and runs the
        // instrumented tests (task :app:ciDebugAndroidTest). Same definition as :hilt-coroutines-runtime.
        managedDevices.localDevices.create("ci") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp-atd"
        }
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // Second half of the Compose Preview workaround above: unit tests must exist for every variant.
        if (keepPreviewUnitTests) (variantBuilder as? HasUnitTestBuilder)?.enableUnitTest = true
    }
}

// The plugin adds the compiler and runtime at its own group and version. This repository intentionally
// substitutes those coordinates with local projects so the sample validates the current source.
if (sampleJitPackVersion == null) {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("$libraryGroupId:hilt-trace-runtime")).using(project(":hilt-trace-runtime"))
            substitute(module("$libraryGroupId:hilt-trace-compiler")).using(project(":hilt-trace-compiler"))
        }
    }
}

// Resolve the actual debug runtime and KSP classpath, not declared dependencies.
val verifySampleJitPack = tasks.register("verifySampleJitPack") {
    group = "verification"
    description = "Checks that the sample resolves hosted JitPack artifacts."
    dependsOn(":sample-feature:verifySampleJitPack")
    if (sampleJitPackVersion == null) {
        doLast { throw GradleException("verifySampleJitPack requires -PsampleJitPackVersion") }
    }
}
if (sampleJitPackVersion != null) {
    afterEvaluate {
        val processor = configurations.filter {
            it.isCanBeResolved && it.name.startsWith("ksp", ignoreCase = true) &&
                it.name.contains("debug", ignoreCase = true) && it.name.contains("processor", ignoreCase = true) &&
                !it.name.contains("test", ignoreCase = true)
        }.single()
        fun componentIds(configuration: org.gradle.api.artifacts.Configuration) =
            configuration.incoming.resolutionResult.rootComponent.map { root ->
                // Inspect component identities without selecting an Android artifact type.
                val ids = linkedSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
                fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    if (!ids.add(component.id)) return
                    component.dependencies.forEach { dependency ->
                        check(dependency is org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                            "Unresolved dependency ${dependency.requested}"
                        }
                        visit(dependency.selected)
                    }
                }
                visit(root)
                ids.map { id ->
                    when (id) {
                        is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                        is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "project:${id.projectPath}"
                        else -> id.displayName
                    }
                }.sorted()
            }
        val runtime = componentIds(configurations.getByName("debugRuntimeClasspath"))
        val compiler = componentIds(processor)
        val version = sampleJitPackVersion
        val expectedGroup = jitPackGroup
        verifySampleJitPack.configure {
            inputs.property("runtimeComponents", runtime)
            inputs.property("compilerComponents", compiler)
            doLast {
                val runtimeIds = inputs.properties.getValue("runtimeComponents") as List<*>
                val compilerIds = inputs.properties.getValue("compilerComponents") as List<*>
                listOf("hilt-trace-runtime", "hilt-coroutines-runtime").forEach { name ->
                    check("$expectedGroup:$name:$version" in runtimeIds && "project::$name" !in runtimeIds) {
                        "Expected hosted $name:$version without a local project in debug runtime: $runtimeIds"
                    }
                }
                check("$expectedGroup:hilt-trace-compiler:$version" in compilerIds &&
                    "project::hilt-trace-compiler" !in compilerIds) {
                    "Expected hosted compiler:$version without a local project in app KSP: $compilerIds"
                }
            }
        }
    }
}

ksp {
    arg("hiltTrace.module", ":app")
}

hiltTrace {
    // The default is debug and benchmark; the minified variant is added so R8 runs over instrumented code.
    variants.set(listOf("debug", "benchmark", "minified"))
    // Opt-out for A/B measurements (docs/BENCHMARK.md, app start): -PhiltTraceEnabled=false builds the same
    // code and build types without instrumentation. The default stays on.
    enabled.set(providers.gradleProperty("hiltTraceEnabled").map { it != "false" }.orElse(true))
    // Opt-in: wraps MembersInjector.injectMembers(T) in a hilt.inject slice. The sample is the only consumer;
    // MembersInjectorTraceTest needs it.
    traceMembersInjectors.set(true)
}

// Proof that tracing survives R8: assembles the minified variant and checks its dex. See docs/VERIFY.md.
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val dexdumpPath = sdkDirectory.map { sdk ->
    sdk.dir("build-tools").asFile.listFiles()
        ?.sortedBy { dir -> dir.name.split('.').joinToString(".") { part -> part.padStart(5, '0') } }
        ?.map { File(it, "dexdump") }
        ?.lastOrNull { it.exists() }
        ?.absolutePath
        ?: error("dexdump not found under ${sdk.asFile}/build-tools")
}
tasks.register<Exec>("verifyMinifiedTracing") {
    group = "verification"
    description = "Assembles the minified variant and checks that HiltTrace call sites survive R8."
    dependsOn("assembleMinified")
    executable = "bash"
    val script = layout.projectDirectory.file("scripts/check-minified-tracing.sh")
    val apk = layout.buildDirectory.file("outputs/apk/minified/app-minified.apk")
    val mapping = layout.buildDirectory.file("outputs/mapping/minified/mapping.txt")
    val dexdump = dexdumpPath
    inputs.file(script)
    // Locals only: a lambda that touched a script-level val would capture the build script and break the
    // configuration cache.
    argumentProviders += CommandLineArgumentProvider {
        listOf(script.asFile.absolutePath, apk.get().asFile.absolutePath, mapping.get().asFile.absolutePath, dexdump.get())
    }
}

// Proof that an R8-minified release stays uninstrumented: assembles release with -PminifyRelease=true and
// checks its dex. Fails fast without the property, so it cannot pass on the default unminified release.
val minifyReleaseRequested = providers.gradleProperty("minifyRelease").orNull == "true"
tasks.register<Exec>("verifyMinifiedReleaseUntraced") {
    group = "verification"
    description = "Assembles release with R8 (-PminifyRelease=true) and checks its dex has no tracing."
    if (minifyReleaseRequested) {
        dependsOn("assembleRelease")
    } else {
        doFirst { throw GradleException("verifyMinifiedReleaseUntraced requires -PminifyRelease=true") }
    }
    executable = "bash"
    val script = layout.projectDirectory.file("scripts/check-release-untraced.sh")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val dexdump = dexdumpPath
    inputs.file(script)
    argumentProviders += CommandLineArgumentProvider {
        listOf(script.asFile.absolutePath, apk.get().asFile.absolutePath, mapping.get().asFile.absolutePath, dexdump.get())
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    if (sampleJitPackVersion == null) {
        implementation(project(":hilt-coroutines-runtime"))
    } else {
        implementation("$jitPackGroup:hilt-coroutines-runtime:$sampleJitPackVersion")
    }
    implementation(project(":sample-feature"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    // tracing-wire does not expose androidx.tracing on the compile classpath, so declare it explicitly.
    implementation(libs.androidx.tracing)
    debugImplementation(libs.androidx.tracing.wire)
    // The minified variant needs a tracing driver too, or R8 has nothing that can enable a category.
    "minifiedImplementation"(libs.androidx.tracing.wire)
    // The Compose tests launch ComponentActivity, which the test manifest declares (debug has it already).
    "minifiedImplementation"(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}