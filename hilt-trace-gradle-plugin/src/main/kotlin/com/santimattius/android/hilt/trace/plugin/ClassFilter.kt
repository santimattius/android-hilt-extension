package com.santimattius.android.hilt.trace.plugin

private const val SWITCHING_PROVIDER_SUFFIX = "\$SwitchingProvider"
private const val HILT_BASE_PREFIX = "Hilt_"
private const val COMPONENT_PREFIX = "Dagger"
private const val COMPONENT_INFIX = "_HiltComponents_"
private const val COMPONENT_IMPL_SUFFIX = "CImpl"

/**
 * Whether [className] (dotted, as AGP reports it) names a class the plugin may instrument:
 * `*$SwitchingProvider`, `Dagger*_HiltComponents_*CImpl` or `Hilt_*`. `*_MembersInjector` is not
 * accepted. AGP asks this for every class on the classpath, so it must not allocate: it only uses
 * index based comparisons and never builds a substring.
 */
internal fun isInstrumentableClassName(className: String): Boolean {
    if (className.endsWith(SWITCHING_PROVIDER_SUFFIX)) return true
    val simpleNameStart = className.lastIndexOf('.') + 1
    if (className.startsWith(HILT_BASE_PREFIX, simpleNameStart)) return true
    return className.startsWith(COMPONENT_PREFIX, simpleNameStart) &&
        className.indexOf(COMPONENT_INFIX, simpleNameStart) >= 0 &&
        className.endsWith(COMPONENT_IMPL_SUFFIX)
}
