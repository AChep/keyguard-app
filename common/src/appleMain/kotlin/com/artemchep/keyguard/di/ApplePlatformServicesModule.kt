package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.PlatformTemporaryArtifactRootRegistry
import com.artemchep.keyguard.common.TemporaryArtifactRoot
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreApple
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.notification.impl.NotificationRepositoryApple
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.copy.PermissionServiceApple
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.platform.AppleStorageDirectoriesImpl
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadServiceApple
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProviderApple
import org.koin.dsl.module

/** Platform services with one Apple implementation, shared by the iOS and macOS roots. */
class ApplePlatformServicesModule {
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
        single<PendingUploadDirProvider> {
            PendingUploadDirProviderApple(directories = get())
        }
        single<EncryptedFilePendingUploadService> {
            EncryptedFilePendingUploadServiceApple(
                dirProvider = get(),
                fileService = get(),
                fileEncryptionCodec = get(),
                stagingSpoolFactory = get(),
            )
        }
        single<NotificationRepository> {
            NotificationRepositoryApple()
        }
        single<PermissionService> {
            PermissionServiceApple(
                windowCoroutineScope = get(),
            )
        }
    }
}
