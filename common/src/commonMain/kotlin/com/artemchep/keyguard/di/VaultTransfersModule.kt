package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupConfigRepositoryImpl
import com.artemchep.keyguard.common.service.backup.BackupDiagnostics
import com.artemchep.keyguard.common.service.backup.BackupRunner
import com.artemchep.keyguard.common.service.download.KeePassAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.KeePassAttachmentSourceLoaderImpl
import com.artemchep.keyguard.common.service.download.KeePassAttachmentSourceResolverImpl
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.export.ExportVaultDataServiceImpl
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.DefaultKeePassAttachmentStorageFactory
import com.artemchep.keyguard.common.service.keepass.KeePassAttachmentReader
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.usecase.BackupSettings
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.ExportLogs
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.UploadGpgPublicKey
import com.artemchep.keyguard.common.usecase.impl.BackupSettingsImpl
import com.artemchep.keyguard.common.usecase.impl.CanPreviewAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.DownloadAttachmentImpl2
import com.artemchep.keyguard.common.usecase.impl.DownloadAttachmentMetadataImpl2
import com.artemchep.keyguard.common.usecase.impl.GetAttachmentPreviewImpl
import com.artemchep.keyguard.common.usecase.impl.MarkBackupAsDirtyImpl
import com.artemchep.keyguard.common.usecase.impl.UploadGpgPublicKeyImpl
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadGarbageCollector
import com.artemchep.keyguard.provider.bitwarden.upload.impl.PendingUploadGarbageCollectorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ExportLogsImpl
import io.ktor.client.HttpClient
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultTransfersModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<BackupConfigRepository> {
                BackupConfigRepositoryImpl(
                    store = get<VaultSettingsKeyValueStore>(),
                    json = get(),
                )
            }

            scoped<MarkBackupAsDirtyImpl>() bind MarkBackupAsDirty::class

            scoped<KeePassAttachmentSourceLoader> {
                KeePassAttachmentSourceLoaderImpl(
                    keePassSourceResolver = KeePassAttachmentSourceResolverImpl(
                        tokenRepository = get<ServiceTokenRepository>(),
                        cipherRepository = get<BitwardenCipherRepository>(),
                        base32Service = get<Base32Service>(),
                    ),
                    keePassAttachmentReader = KeePassAttachmentReader(
                        base64Service = get<Base64Service>(),
                        storageFactory = DefaultKeePassAttachmentStorageFactory(
                            fileService = get<FileService>(),
                            webDavClientFactory = KtorWebDavClientFactory(
                                httpClient = get<HttpClient>(),
                            ),
                        ),
                        stagingSpoolFactory = get<StagingSpoolFactory>(),
                    ),
                )
            }

            scoped<ExportVaultDataServiceImpl>() bind ExportVaultDataService::class

            scoped<BackupRunner> {
                BackupRunner(
                    exportVaultDataService = get(),
                    backupRepository = get(),
                    backupObjectStoreFactory = get(),
                    cryptoGenerator = get(),
                    base64Service = get(),
                    dateFormatter = get(),
                    downloadSourceLoader = get(),
                    downloadAttachmentMetadata = get(),
                    diagnostics = BackupDiagnostics(logRepository = get<LogRepository>()),
                )
            }

            scoped<DownloadAttachmentImpl2>() bind DownloadAttachment::class

            scoped<DownloadAttachmentMetadataImpl2>() bind DownloadAttachmentMetadata::class

            scoped<GetAttachmentPreviewImpl>() bind GetAttachmentPreview::class

            scoped<CanPreviewAttachmentImpl>() bind CanPreviewAttachment::class

            scoped<BackupSettingsImpl>() bind BackupSettings::class

            scoped<UploadGpgPublicKeyImpl>() bind UploadGpgPublicKey::class

            scoped<ExportLogsImpl>() bind ExportLogs::class

            scoped<PendingUploadGarbageCollectorImpl> {
                PendingUploadGarbageCollectorImpl(
                    db = get(),
                    pendingUploadCoordinator = get(),
                    logRepository = get(),
                )
            } bind PendingUploadGarbageCollector::class
        }
    }
}
