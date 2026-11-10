package com.santimattius.android.hilt.trace

/**
 * Test stand-in for the runtime facade: same fully qualified name and JVM signatures as the real
 * `HiltTrace` (`begin(String, String): Object`, `end(Object)`), recording each call. The token is
 * the slice name, so a mismatched `end` is visible in [events].
 */
internal object HiltTrace {
    val events: MutableList<String> = mutableListOf()

    @JvmStatic
    fun begin(category: String, name: String): Any? {
        events += "begin $category $name"
        return name
    }

    @JvmStatic
    fun end(token: Any?) {
        events += "end $token"
    }
}
