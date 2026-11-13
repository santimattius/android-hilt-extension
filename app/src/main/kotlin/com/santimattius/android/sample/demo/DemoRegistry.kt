package com.santimattius.android.sample.demo

import javax.inject.Inject

/** The coroutine-based demos by card. The Android-bound cards (activity, view, service) are not here. */
class DemoRegistry internal constructor(private val demos: Map<DemoId, SuspendDemo>) {

    @Inject
    constructor(
        singleton: SingletonBindingDemo,
        dispatchers: DispatchersDemo,
        suspendProvider: SuspendProviderDemo,
        suspendLazy: SuspendLazyDemo,
        lazyAwait: LazyAwaitDemo,
        traceCoroutine: TraceCoroutineDemo,
        blocking: BlockingResolutionDemo,
        complexGraph: ComplexGraphDemo,
        mainThreadGraph: MainThreadGraphDemo,
    ) : this(
        mapOf(
            DemoId.SINGLETON_BINDING to singleton,
            DemoId.DISPATCHERS to dispatchers,
            DemoId.SUSPEND_PROVIDER to suspendProvider,
            DemoId.SUSPEND_LAZY to suspendLazy,
            DemoId.LAZY_AWAIT to lazyAwait,
            DemoId.TRACE_COROUTINE to traceCoroutine,
            DemoId.BLOCKING_RESOLUTION to blocking,
            DemoId.COMPLEX_GRAPH to complexGraph,
            DemoId.COMPLEX_GRAPH_MAIN_THREAD to mainThreadGraph,
        ),
    )

    fun find(id: DemoId): SuspendDemo? = demos[id]
}
