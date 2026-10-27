package com.artemchep.keyguard.di

import com.artemchep.keyguard.appleKoinModules
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import com.artemchep.keyguard.platform.AppleStorageDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Keeps production storage services and gives all of them the same test locations.
 * The [overrides] replace other production definitions for this graph only.
 */
internal suspend fun <T> AppleStorageDirectories.withKoin(
    vararg overrides: Module,
    block: suspend (Koin) -> T,
): T {
    val directories = this
    val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Default + job)
    val storageModule = module {
        single<AppleStorageDirectories> { directories }
        single<WindowCoroutineScope> { WindowCoroutineScopeImpl(scope = scope, showMessage = get()) }
    }
    var application: KoinApplication? = null
    try {
        application = koinApplication {
            allowOverride(true)
            modules(appleKoinModules() + storageModule + overrides)
        }
        return block(application.koin)
    } finally {
        withContext(NonCancellable) {
            try {
                job.cancelAndJoin()
            } finally {
                application?.close()
            }
        }
    }
}

/** Vault jobs use their own production scope, so await it before deleting test files. */
internal suspend fun <T> Koin.withVaultSession(masterKey: MasterKey, block: suspend (VaultSession) -> T): T {
    val session = get<VaultSessionFactory>().create(masterKey)
    val job = checkNotNull(session.resolve {
        getSource<VaultScopeSource>()?.coroutineScope?.coroutineContext?.get(Job)
    })
    try {
        return block(session)
    } finally {
        withContext(NonCancellable) {
            session.close()
            job.join()
        }
    }
}
