package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.compiler.catalogue.InjectionTarget

private const val INJECT_PREFIX = "inject"
private val SINGLE_TARGET_DESCRIPTOR = Regex("\\(L([^;\\[(]+);\\)V")

/**
 * Rule J: the catalogue's injection targets, looked up from a method name and JVM descriptor. A hit
 * names the slice `Target.members`, with `Target` the simple name of the class that receives the
 * injection. Only a method named `inject*` that returns void and takes exactly one parameter, of a
 * catalogued type, matches; a private helper such as `injectMainActivity2` returns the instance, so
 * its descriptor never matches, and whether the method is public, non-static and non-synthetic is
 * decided by the rule engine ([instrumentAccessors]), which wraps whatever this answers for.
 *
 * `*_MembersInjector.injectMembers` has the same signature but is never offered: rule M is cut
 * because the component calls the static setters, so `MembersInjector<T>` requested through DI is
 * not traced.
 *
 * Catalogue types are dotted (`p.Outer.Inner`); descriptors write nested classes with `$`, which is
 * read as a dot here. An empty index means rule J does nothing.
 */
internal class InjectionNames(targets: List<InjectionTarget>) {

    private val types = targets.mapTo(HashSet()) { it.type.replace('$', '.') }

    val isEmpty: Boolean get() = types.isEmpty()

    fun nameOf(method: String, descriptor: String): String? {
        if (!method.startsWith(INJECT_PREFIX)) return null
        val internalName = SINGLE_TARGET_DESCRIPTOR.matchEntire(descriptor)?.groupValues?.get(1) ?: return null
        val type = internalName.replace('/', '.').replace('$', '.')
        return if (type in types) "${type.substringAfterLast('.')}.members" else null
    }
}
