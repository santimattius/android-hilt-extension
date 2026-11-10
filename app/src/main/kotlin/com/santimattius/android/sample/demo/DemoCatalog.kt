package com.santimattius.android.sample.demo

/** One validation card of the sample, per library promise. */
enum class DemoId {
    ACTIVITY_INJECTION,
    VIEWMODEL_INJECTION,
    VIEW_INJECTION,
    SERVICE_INJECTION,
    SINGLETON_BINDING,
    DISPATCHERS,
    SUSPEND_PROVIDER,
    SUSPEND_LAZY,
    LAZY_AWAIT,
    TRACE_COROUTINE,
    BLOCKING_RESOLUTION,
}

/**
 * What a card promises and which trace section a recorded trace shows for it
 * ([traceSection] is `category name`, or null when the action produces none).
 */
data class DemoSpec(
    val id: DemoId,
    val title: String,
    val promise: String,
    val traceSection: String?,
)

object DemoCatalog {
    const val TRACE_COROUTINE_CATEGORY = "sample.coroutine"
    const val TRACE_COROUTINE_NAME = "refresh"

    val all: List<DemoSpec> = listOf(
        DemoSpec(
            DemoId.ACTIVITY_INJECTION,
            "Activity injection",
            "Field injection of an @AndroidEntryPoint activity is traced. Run recreates the activity.",
            "hilt.inject MainActivity.members",
        ),
        DemoSpec(
            DemoId.VIEWMODEL_INJECTION,
            "ViewModel creation",
            "A @HiltViewModel is created through a traced binding. Run creates a new instance.",
            "hilt.create ${SAMPLE_PACKAGE}.android.ScratchViewModel",
        ),
        DemoSpec(
            DemoId.VIEW_INJECTION,
            "View injection",
            "Field injection of an @AndroidEntryPoint view is traced. Run inflates a new view.",
            "hilt.inject DemoView.members",
        ),
        DemoSpec(
            DemoId.SERVICE_INJECTION,
            "Service injection",
            "Field injection of an @AndroidEntryPoint service is traced. Run starts the service.",
            "hilt.inject DemoService.members",
        ),
        DemoSpec(
            DemoId.SINGLETON_BINDING,
            "Singleton binding",
            "A scoped binding is created once; later requests reuse it and add no create section.",
            "hilt.create ${DEMO_PACKAGE}.SingletonCounter (first run only)",
        ),
        DemoSpec(
            DemoId.DISPATCHERS,
            "Qualified dispatchers and scope",
            "@IoDispatcher, @DefaultDispatcher, @MainDispatcher and @ApplicationScope are injectable.",
            null,
        ),
        DemoSpec(
            DemoId.SUSPEND_PROVIDER,
            "SuspendProvider",
            "Provider.suspending(dispatcher) resolves a binding without blocking the caller.",
            "hilt.create ${DEMO_PACKAGE}.FreshWidget (every get)",
        ),
        DemoSpec(
            DemoId.SUSPEND_LAZY,
            "SuspendLazy",
            "A suspending initializer runs once, even with concurrent callers.",
            null,
        ),
        DemoSpec(
            DemoId.LAZY_AWAIT,
            "Lazy.await",
            "dagger.Lazy is resolved on a dispatcher of your choice, off the calling thread.",
            "hilt.create ${DEMO_PACKAGE}.ExpensiveService",
        ),
        DemoSpec(
            DemoId.TRACE_COROUTINE,
            "traceCoroutine",
            "A trace section that follows a coroutine across suspension points and threads.",
            "$TRACE_COROUTINE_CATEGORY $TRACE_COROUTINE_NAME",
        ),
        DemoSpec(
            DemoId.BLOCKING_RESOLUTION,
            "Blocking resolution",
            "Cancelling a caller does not interrupt a get() that already started; it is rethrown afterwards.",
            null,
        ),
    )
}

private const val SAMPLE_PACKAGE = "com.santimattius.android.sample"
private const val DEMO_PACKAGE = "$SAMPLE_PACKAGE.demo"
