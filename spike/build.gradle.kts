// Throwaway slice 0 spike: not part of release wiring; delete with the spike/ directory.
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp") version "2.3.13"
    id("com.google.dagger.hilt.android") version "2.60.1"
}

val androidMinSdkVersion: String by project
val androidTargetSdkVersion: String by project

android {
    namespace = "spike.app"
    compileSdk = androidTargetSdkVersion.toInt()

    defaultConfig {
        applicationId = "spike.app"
        minSdk = androidMinSdkVersion.toInt()
        targetSdk = androidTargetSdkVersion.toInt()
        versionCode = 1
        versionName = "0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp { arg("hiltTrace.module", ":spike") }

dependencies {
    implementation(project(":spike:lib"))
    ksp(project(":spike:processor"))
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.tracing:tracing:2.0.5")
    androidTestImplementation("androidx.tracing:tracing-wire:2.0.5")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Slice 0.6 measurement: a pass-through factory that only applies the production name filter and
// records every class it is asked about. Scope is chosen with -PinstrScope=ALL|PROJECT (default: none);
// -PinstrRun=<n> only varies the log path so every measurement is a cold, uncached run.
interface CountParams : InstrumentationParameters {
    @get:Input val log: Property<String>
}

abstract class CountingFactory : AsmClassVisitorFactory<CountParams> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor) = nextClassVisitor

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        val simple = name.substringAfterLast('.')
        val match = simple.endsWith("\$SwitchingProvider") || simple.endsWith("_MembersInjector") ||
            simple.startsWith("Hilt_") || (simple.startsWith("Dagger") && simple.contains("_HiltComponents_") && simple.endsWith("CImpl"))
        synchronized(CountingFactory::class.java) {
            File(parameters.get().log.get()).appendText((if (match) "M " else "V ") + name + "\n")
        }
        return match
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val scope = providers.gradleProperty("instrScope").orNull ?: return@onVariants
        val run = providers.gradleProperty("instrRun").orNull ?: "0"
        val log = layout.buildDirectory.file("spike-visited-$scope-$run.txt").get().asFile
        log.parentFile.mkdirs()
        log.writeText("")
        variant.instrumentation.transformClassesWith(CountingFactory::class.java, InstrumentationScope.valueOf(scope)) {
            it.log.set(log.absolutePath)
        }
    }
}
