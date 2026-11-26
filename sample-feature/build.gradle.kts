plugins {
    id("hiltext.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.santimattius.android.sample.feature"
}

ksp {
    arg("hiltTrace.module", ":sample-feature")
}

val sampleJitPackVersion = providers.gradleProperty("sampleJitPackVersion").orNull
val jitPackGroup = "com.github.santimattius.android-hilt-extension"

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    if (sampleJitPackVersion == null) {
        ksp(project(":hilt-trace-compiler"))
    } else {
        ksp("$jitPackGroup:hilt-trace-compiler:$sampleJitPackVersion")
    }
}

val verifySampleJitPack = tasks.register("verifySampleJitPack") {
    group = "verification"
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
        val components = processor.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier }.map { id ->
                when (id) {
                    is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "project:${id.projectPath}"
                    else -> id.displayName
                }
            }.sorted()
        }
        val version = sampleJitPackVersion
        val expectedGroup = jitPackGroup
        verifySampleJitPack.configure {
            inputs.property("compilerComponents", components)
            doLast {
                val ids = inputs.properties.getValue("compilerComponents") as List<*>
                check("$expectedGroup:hilt-trace-compiler:$version" in ids &&
                    "project::hilt-trace-compiler" !in ids) {
                    "Expected hosted compiler:$version without a local project in feature KSP: $ids"
                }
            }
        }
    }
}
