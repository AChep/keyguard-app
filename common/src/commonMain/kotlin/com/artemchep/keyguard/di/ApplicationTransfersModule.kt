package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.backup.BackupDiagnostics
import com.artemchep.keyguard.common.service.backup.BackupRepository
import com.artemchep.keyguard.common.service.backup.BackupRepositoryZipImpl
import com.artemchep.keyguard.common.service.backup.BackupRunService
import com.artemchep.keyguard.common.service.backup.S3BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportService
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Exporter
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoaderImpl
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.service.download.DownloadServiceImpl
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadTaskImpl
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.s3.KtorS3ClientFactory
import com.artemchep.keyguard.common.service.s3.S3ClientFactory
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.usecase.CheckS3Connection
import com.artemchep.keyguard.common.usecase.GpgKeyExport
import com.artemchep.keyguard.common.usecase.GpgKeyPrivateExport
import com.artemchep.keyguard.common.usecase.GpgKeyPublicExport
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.usecase.ListS3Directory
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.impl.CheckS3ConnectionImpl
import com.artemchep.keyguard.common.usecase.impl.GpgKeyExportImpl
import com.artemchep.keyguard.common.usecase.impl.GpgKeyPrivateExportImpl
import com.artemchep.keyguard.common.usecase.impl.GpgKeyPublicExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPairExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPrivateExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPublicExportImpl
import com.artemchep.keyguard.common.usecase.impl.ListS3DirectoryImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveAttachmentImpl
import com.artemchep.keyguard.crypto.NativeSshKeyPkcs8Exporter
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.impl.PendingUploadCoordinatorImpl
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class ApplicationTransfersModule {
    val module = module {
        single<DownloadTask> {
            DownloadTaskImpl(
                httpClient = get(),
                fileEncryptionCodec = get(),
                stagingSpoolFactory = get(),
            )
        }

        single<DownloadAttachmentSourceLoaderImpl>() bind DownloadAttachmentSourceLoader::class

        single<KtorWebDavClientFactory>() bind WebDavClientFactory::class

        single<KtorS3ClientFactory>() bind S3ClientFactory::class

        single<S3BackupObjectStoreFactory>()

        single<CheckS3ConnectionImpl>() bind CheckS3Connection::class

        single<ListS3DirectoryImpl>() bind ListS3Directory::class

        single<SshKeyPkcs8Exporter> {
            NativeSshKeyPkcs8Exporter
        }

        single<DownloadServiceImpl>() bind DownloadService::class

        single<RemoveAttachmentImpl>() bind RemoveAttachment::class

        single<PendingUploadCoordinatorImpl>() bind PendingUploadCoordinator::class

        single<JsonExportServiceImpl>() bind JsonExportService::class

        single<CxfExportService> {
            CxfExportServiceImpl(
                passkeyCrypto = get(),
                sshKeyPkcs8Exporter = get(),
            )
        }

        single<KeyPairExportImpl>() bind KeyPairExport::class

        single<KeyPublicExportImpl>() bind KeyPublicExport::class

        single<KeyPrivateExportImpl>() bind KeyPrivateExport::class

        single<GpgKeyExportImpl>() bind GpgKeyExport::class

        single<GpgKeyPublicExportImpl>() bind GpgKeyPublicExport::class

        single<GpgKeyPrivateExportImpl>() bind GpgKeyPrivateExport::class

        single<BackupRepositoryZipImpl>() bind BackupRepository::class

        single<BackupRunService> {
            BackupRunService(
                getVaultSession = get(),
                backupConfigSessionAccess = get(),
                backupRunnerSessionAccess = get(),
                sessionReadRepository = get(),
                vaultSessionLocker = get(),
                diagnostics = BackupDiagnostics(logRepository = get<LogRepository>()),
            )
        }
    }
}
