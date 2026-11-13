package io.github.santimattius.android.hilt.trace.plugin.asm

import io.github.santimattius.android.hilt.trace.compiler.catalogue.Binding
import io.github.santimattius.android.hilt.trace.compiler.catalogue.BindingKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SliceNamesTest {

    private val viewModel = "spike.SpikeViewModel"
    private val lifecycleModule = "dagger.hilt.android.internal.managers.ActivityRetainedComponentManager.LifecycleModule"

    private fun body(fixture: String) = (locateCaseBodies(readClass(fixtureBytes(fixture)).methods.single { it.name == "get" }) as Dispatch.Cases).bodies.single()

    private fun binding(kind: BindingKind, type: String, qualifier: String?, owner: String, method: String = "<init>") =
        Binding(kind, type, qualifier, null, owner, method)

    @Test
    fun `a constructor binding adds its qualifier to the slice name`() {
        val names = SliceNames(listOf(binding(BindingKind.INJECT, viewModel, "javax.inject.Named(value=vm)", viewModel)))
        assertEquals("$viewModel@javax.inject.Named(value=vm)", names.nameOf(body("ViewModelCImpl\$SwitchingProvider")))
    }

    @Test
    fun `a provides binding is joined through its module factory`() {
        val names = SliceNames(listOf(binding(BindingKind.PROVIDES, "dagger.hilt.android.ActivityRetainedLifecycle", null, lifecycleModule, "provideActivityRetainedLifecycle")))
        assertEquals("dagger.hilt.android.ActivityRetainedLifecycle", names.nameOf(body("ActivityRetainedCImpl\$SwitchingProvider")))
    }

    @Test
    fun `without a catalogue entry the name falls back to the first constructed type or factory`() {
        val unrelated = binding(BindingKind.BINDS, viewModel, "q", viewModel)
        assertEquals(viewModel, SliceNames(listOf(unrelated)).nameOf(body("ViewModelCImpl\$SwitchingProvider")))
        assertEquals(
            "dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory",
            SliceNames(emptyList()).nameOf(body("ActivityRetainedCImpl\$SwitchingProvider")),
        )
    }
}
