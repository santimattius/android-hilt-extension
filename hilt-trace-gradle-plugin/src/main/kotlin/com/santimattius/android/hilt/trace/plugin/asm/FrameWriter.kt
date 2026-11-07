package com.santimattius.android.hilt.trace.plugin.asm

import org.objectweb.asm.ClassWriter

/**
 * A [ClassWriter] that computes frames without loading classes: the plugin runs in Gradle, where the
 * app's classes are not on the class path, so the default `getCommonSuperClass` would throw. Case
 * bodies are straight-line code, so no join point merges two different reference types and the
 * `Object` answer is never actually needed; the verifier test guards that assumption.
 */
internal fun framesWriter(): ClassWriter = object : ClassWriter(COMPUTE_FRAMES) {
    override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
}
