package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/** Resolves the dispatcher registered by the platform module under the [DatabaseDispatcher] qualifier. */
internal fun Scope.databaseDispatcher(): CoroutineDispatcher =
    get<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>())
