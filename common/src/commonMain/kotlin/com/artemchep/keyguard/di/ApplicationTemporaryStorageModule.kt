package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.PlatformTemporaryArtifactRootRegistry
import com.artemchep.keyguard.common.TemporaryArtifactMaintenance
import com.artemchep.keyguard.common.TemporaryArtifactMaintenanceImpl
import com.artemchep.keyguard.common.TemporaryArtifactRoot
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.dsl.module

/** Staging and cleanup services backed by the platform's temporary storage. */
internal class ApplicationTemporaryStorageModule {
    val module = module {
        single<StagingSpoolObserver> {
            StagingSpoolObserver.NoOp
        }

        single<StagingSpoolFactory> {
            DefaultStagingSpoolFactory(
                observer = get(),
                directoryProvider = get(),
            )
        }

        single<TemporaryArtifactMaintenance> {
            val temporaryDirProvider = get<PrivateTemporaryDirProvider>()
            TemporaryArtifactMaintenanceImpl(
                roots = buildList {
                    add(
                        TemporaryArtifactRoot(
                            label = "private-temporary",
                            provideDirectory = {
                                withContext(Dispatchers.IO) {
                                    temporaryDirProvider.get()
                                }
                            },
                        ),
                    )
                    getOrNull<CacheDirProvider>()?.let { cacheDirProvider ->
                        add(
                            TemporaryArtifactRoot(
                                label = "cache",
                                provideDirectory = cacheDirProvider::get,
                            ),
                        )
                    }
                    addAll(get<PlatformTemporaryArtifactRootRegistry>().values)
                },
                sweeper = { directory, olderThan ->
                    withContext(Dispatchers.IO) {
                        sweepTemporaryArtifacts(
                            directory = directory,
                            olderThan = olderThan,
                        )
                    }
                },
                logRepository = get(),
            )
        }
    }
}
