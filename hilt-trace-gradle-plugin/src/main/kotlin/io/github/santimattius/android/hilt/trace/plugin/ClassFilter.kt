package io.github.santimattius.android.hilt.trace.plugin

private const val SWITCHING_PROVIDER_SUFFIX = "\$SwitchingProvider"
private const val HILT_BASE_PREFIX = "Hilt_"
private const val COMPONENT_PREFIX = "Dagger"
private const val COMPONENT_INFIX = "_HiltComponents_"
private const val COMPONENT_IMPL_SUFFIX = "CImpl"
private const val MEMBERS_INJECTOR_SUFFIX = "_MembersInjector"

/**
 * Whether [className] (dotted, as AGP reports it) names a class the plugin may instrument:
 * `*$SwitchingProvider`, `Dagger*_HiltComponents_*CImpl` or `Hilt_*`. `*_MembersInjector` is not
 * accepted here: it is admitted only when the opt-in `traceMembersInjectors` is on, see
 * [isMembersInjectorName]. AGP asks this for every class on the classpath, so it must not allocate: it only uses
 * index based comparisons and never builds a substring.
 */
internal fun isInstrumentableClassName(className: String): Boolean {
    if (className.endsWith(SWITCHING_PROVIDER_SUFFIX) || isAssistedFactoryName(className)) return true
    val simpleNameStart = className.lastIndexOf('.') + 1
    if (className.startsWith(HILT_BASE_PREFIX, simpleNameStart)) return true
    return isComponentImplementationName(className)
}

/** Whether [className] (dotted) is a generated component implementation, `Dagger*_HiltComponents_*CImpl`. Allocation free. */
internal fun isComponentImplementationName(className: String): Boolean {
    val simpleNameStart = className.lastIndexOf('.') + 1
    return className.startsWith(COMPONENT_PREFIX, simpleNameStart) &&
        className.indexOf(COMPONENT_INFIX, simpleNameStart) >= 0 &&
        className.endsWith(COMPONENT_IMPL_SUFFIX)
}

/**
 * Whether [className] (dotted) is `*$SwitchingProvider$N`, N one or more digits: the anonymous class Hilt
 * generates for an `@AssistedFactory` or `@HiltWorker` factory. Allocation free.
 */
internal fun isAssistedFactoryName(className: String): Boolean {
    var digits = className.length
    while (digits > 0 && className[digits - 1] in '0'..'9') digits--
    if (digits == className.length || digits == 0 || className[digits - 1] != '$') return false
    return className.regionMatches(digits - 1 - SWITCHING_PROVIDER_SUFFIX.length, SWITCHING_PROVIDER_SUFFIX, 0, SWITCHING_PROVIDER_SUFFIX.length)
}

/** Whether [className] (dotted or internal) is a generated `Target_MembersInjector`. Allocation free. */
internal fun isMembersInjectorName(className: String): Boolean =
    className.length > MEMBERS_INJECTOR_SUFFIX.length && className.endsWith(MEMBERS_INJECTOR_SUFFIX)
