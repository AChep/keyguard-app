package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.PlatformTemporaryArtifactRootRegistry
import com.artemchep.keyguard.common.TemporaryArtifactRoot
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreApple
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.platform.AppleStorageDirectoriesImpl
import org.koin.dsl.module

/** Apple directories and cleanup roots shared by staging and artifact maintenance. */
internal class AppleTemporaryStorageModule {
    val module = module {
        single<AppleStorageDirectories> {
            AppleStorageDirectoriesImpl()
        }
        single<PrivateTemporaryDirProvider> {
            val directories = get<AppleStorageDirectories>()
            PrivateTemporaryDirProvider { directories.temporary }
        }
        single {
            val directories = get<AppleStorageDirectories>()
            PlatformTemporaryArtifactRootRegistry(
                listOf(
                    TemporaryArtifactRoot(
                        label = "apple-data",
                        provideDirectory = { directories.data.path },
                    ),
                    TemporaryArtifactRoot(
                        label = "apple-downloads",
                        provideDirectory = { DownloadFileStoreApple.getDir(directories).path },
                    ),
                ),
            )
        }
    }
}
