package com.artemchep.keyguard.di

/**
 * Qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope] owned by the
 * Koin graph. Work launched into it ends when the application graph closes.
 */
object ApplicationCoroutineScope
