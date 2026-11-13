package io.github.santimattius.android.sample.greeting

import io.github.santimattius.android.sample.di.AUDIENCE
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Scoped binding that also requests a qualified `@Provides` value and the unscoped formatter. */
@Singleton
class GreetingRepository @Inject constructor(
    private val formatter: GreetingFormatter,
    @Named(AUDIENCE) private val audience: String,
) {

    fun audienceName(): String = formatter.displayName(audience)
}
