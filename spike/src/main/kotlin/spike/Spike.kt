package spike

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@HiltAndroidApp
class SpikeApp : Application()

/** Scoped class binding. */
@Singleton
class ScopedService @Inject constructor()

/** Unscoped binding that is only ever requested through Provider<T>. */
class ViaProvider @Inject constructor()

/** Unscoped binding requested directly (expected to be constructed inline). */
class Direct @Inject constructor()

/** Unscoped class that mixes constructor and field injection. */
class FieldInjected @Inject constructor() { @Inject lateinit var scoped: ScopedService }

interface Greeter { fun hi(): String }
class GreeterImpl @Inject constructor() : Greeter { override fun hi() = "hi" }

class Provided(val tag: String)

/** Unscoped consumer of a Provider<T> and of a direct dependency. */
class Consumer @Inject constructor(
    val viaProvider: Provider<ViaProvider>,
    val direct: Direct,
    val scoped: ScopedService,
)

class AssistedThing @AssistedInject constructor(
    val scoped: ScopedService,
    @Assisted val label: String,
) {
    @AssistedFactory
    interface Factory { fun create(label: String): AssistedThing }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SpikeBindsModule {
    @Binds abstract fun greeter(impl: GreeterImpl): Greeter

    @Binds @IntoSet abstract fun setItem(impl: GreeterImpl): Greeter
}

@Module
@InstallIn(SingletonComponent::class)
object SpikeProvidesModule {
    @Provides @Singleton fun scopedProvided(): Provided = Provided("scoped")

    @Provides @Named("unscoped") fun unscopedProvided(): Provided = Provided("unscoped")

    @Provides @IntoSet fun setItem2(): Greeter = object : Greeter { override fun hi() = "set2" }
}

@HiltViewModel
class SpikeViewModel @Inject constructor(
    val scoped: ScopedService,
    val viaProvider: Provider<ViaProvider>,
) : ViewModel()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var consumer: Consumer
    @Inject lateinit var greeters: Set<@JvmSuppressWildcards Greeter>
    @Inject lateinit var factory: AssistedThing.Factory
    @Inject lateinit var viaProvider: Provider<ViaProvider>
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpikeEntryPoint {
    fun consumer(): Consumer
    fun scoped(): ScopedService
    fun direct(): Direct
    fun greeter(): Greeter
    fun greeters(): Set<@JvmSuppressWildcards Greeter>
    fun viaProvider(): Provider<ViaProvider>
    @Named("unscoped") fun unscopedProvided(): Provided
    fun many(): Many
    fun fieldInjected(): FieldInjected
    fun provided(): Provided
    @Named("unscoped") fun unscopedProvidedProvider(): Provider<Provided>
}
