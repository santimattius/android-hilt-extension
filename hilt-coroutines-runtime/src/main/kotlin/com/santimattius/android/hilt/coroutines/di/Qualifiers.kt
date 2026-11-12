package com.santimattius.android.hilt.coroutines.di

import javax.inject.Qualifier

/** Qualifies the [kotlinx.coroutines.CoroutineDispatcher] for blocking I/O work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class IoDispatcher

/** Qualifies the [kotlinx.coroutines.CoroutineDispatcher] for CPU-bound work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class DefaultDispatcher

/** Qualifies the [kotlinx.coroutines.CoroutineDispatcher] of the Android main thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class MainDispatcher

/** Qualifies the long-lived [kotlinx.coroutines.CoroutineScope] owned by the Hilt component. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ApplicationScope
