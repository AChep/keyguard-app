package com.artemchep.keyguard.core.session

import arrow.core.partially1
import arrow.optics.Getter
import com.artemchep.autotype.biometricsIsSupported
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.service.backup.BackupLocalObjectStoreFactoryTag
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupSchedulerWorker
import com.artemchep.keyguard.common.service.backup.LocalFolderBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.SelectableBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.WebDavBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.DownloadRepositoryInMemory
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerNoOp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreDesktop
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.impl.TextServiceImpl
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.copy.ClipboardServiceJvm
import com.artemchep.keyguard.copy.ConnectivityServiceJvm
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.DesktopKeyValueStoreFactory
import com.artemchep.keyguard.copy.FileWatcherServiceJvm
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.PermissionServiceJvm
import com.artemchep.keyguard.copy.PowerServiceJvm
import com.artemchep.keyguard.copy.ReviewServiceJvm
import com.artemchep.keyguard.copy.atomicDataDirectory
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.GlobalModuleJvm
import com.artemchep.keyguard.feature.biometric.BiometricKeyRepositoryDesktop
import com.artemchep.keyguard.feature.biometric.BiometricPromptHost
import com.artemchep.keyguard.feature.biometric.BiometricPromptHostKeychain
import com.artemchep.keyguard.feature.biometric.BiometricPromptHostLinux
import com.artemchep.keyguard.feature.biometric.BiometricPromptHostWindowsHello
import com.artemchep.keyguard.feature.navigation.NavigationModule
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProviderDesktop
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.traverse
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

class BiometricStatusUseCaseImpl(
    private val promptHost: BiometricPromptHost,
) : BiometricStatusUseCase {

    override fun invoke(): Flow<BiometricStatus> = flow {
        // Only the platforms that have a biometric backend
        // get to probe the native library.
        val hasBiometrics = when (CurrentPlatform) {
            is Platform.Desktop.MacOS,
            is Platform.Desktop.Windows,
            is Platform.Desktop.Linux,
                -> biometricsIsSupported()

            else -> false
        }
        val event = if (hasBiometrics) {
            BiometricStatus.Available(
                createCipher = promptHost::createCipher,
            )
        } else {
            BiometricStatus.Unavailable
        }
        emit(event)
    }
}

class GetSuggestionsImpl : GetSuggestions<Any?> {
    override fun invoke(
        p1: List<Any?>,
        p2: Getter<Any?, DSecret>,
        p3: AutofillTarget,
        p4: EquivalentDomainsBuilderFactory,
    ): IO<List<Any?>> = kotlin.run {
        val msg = "Autofill suggestions are not supported on desktop."
        ioRaise(RuntimeException(msg))
    }
}

class GetPurchasedImpl : GetPurchased {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

class CleanUpAttachmentImpl : CleanUpAttachment {
    override fun invoke(): IO<Int> = kotlin.run {
        val msg = "Attachment cleanup is not supported on desktop."
        ioRaise(RuntimeException(msg))
    }
}

class AutofillServiceAndroid : AutofillService {
    override fun status(): Flow<AutofillServiceStatus> = emptyFlow()
}

class SubscriptionServiceAndroid : SubscriptionService {
    override fun purchased(): Flow<RichResult<Boolean>> = flowOf(RichResult.Success(true))

    override fun subscriptions(): Flow<List<Subscription>?> = flowOf(null)

    override fun products(): Flow<List<Product>?> = flowOf(null)
}

class ClearDataAndroid(
    private val logRepository: LogRepository,
    private val dataDirectory: DataDirectory,
) : ClearData {
    companion object {
        private const val TAG = "ClearData"
    }

    override fun invoke(): IO<Unit> = ioEffect {
        val ios = listOf(
            dataDirectory.data().flatMap(::delete.partially1("data")),
            dataDirectory.cache().flatMap(::delete.partially1("cache")),
            dataDirectory.config().flatMap(::delete.partially1("config")),
        )
        ios
            .parallel()
            .bind()
    }

    private fun delete(
        tag: String,
        path: String,
    ): IO<Unit> = ioEffect(Dispatchers.IO) {
        val filesToDelete = File(path)
            .traverse()
            .map { file ->
                val deleted = file.delete()
                if (!deleted) {
                    file.deleteOnExit()
                }
                file to deleted
            }
            .toList()

        val allCount = filesToDelete.size
        val deletedCount = filesToDelete.count { it.second }
        if (allCount == deletedCount) {
            // Also delete directories.
            File(path).deleteRecursively()
        }

        logRepository.post(
            tag = TAG,
            message = "Deleted '$tag' directory: $deletedCount deleted files, " +
                "${allCount - deletedCount} to delete on exit.",
            level = com.artemchep.keyguard.common.service.logging.LogLevel.INFO,
        )
    }
}

class CacheDirProviderJvm(
    private val dataDirectory: DataDirectory,
) : CacheDirProvider {

    override suspend fun get(): LocalPath {
        val path = dataDirectory.cache().bind()
        return LocalPath(path)
    }

    override fun getBlocking(): LocalPath {
        val path = dataDirectory.cacheBlocking()
        return LocalPath(path)
    }
}

class PlatformApplicationModule {
    val module = module {
        includes(GlobalModuleJvm().module)
        single { PlatformLinkInfoExtractorRegistry(emptyList()) }
        single { PlatformLogSinkRegistry(listOf(get<LogRepositoryKotlin>())) }
        single { WorkerRegistry(listOf(get<BackupSchedulerWorker>())) }

        factory<LeContext> {
            LeContext()
        }
        single<AndroidIpcRegistrationService> {
            AndroidIpcRegistrationServiceNone
        }
        single<BackupObjectStoreFactory>(qualifier = named(BackupLocalObjectStoreFactoryTag)) {
            LocalFolderBackupObjectStoreFactory()
        }
        single<BackupObjectStoreFactory> {
            SelectableBackupObjectStoreFactory(
                localFactory = get(qualifier = named(BackupLocalObjectStoreFactoryTag)),
                webDavFactory = WebDavBackupObjectStoreFactory(
                    httpClient = get<HttpClient>(),
                ),
            )
        }
        single {
            FlavorConfig(
                isFreeAsBeer = false,
            )
        }
        single<BiometricStatusUseCase> {
            BiometricStatusUseCaseImpl(
                promptHost = get(),
            )
        }
        single<BiometricKeyRepository> {
            BiometricKeyRepositoryDesktop(
                keychainRepository = get(),
            )
        }
        single<BiometricPromptHost> {
            when (CurrentPlatform) {
                is Platform.Desktop.Windows -> BiometricPromptHostWindowsHello(
                    cryptoGenerator = get(),
                )
                is Platform.Desktop.Linux -> BiometricPromptHostLinux()
                else -> BiometricPromptHostKeychain(
                    base64Service = get(),
                    cryptoGenerator = get(),
                    keychainRepository = get(),
                )
            }
        }
        single<YubiKeyUnlockAvailability> {
            YubiKeyUnlockAvailability { false }
        }
        single<GetBarcodeImage> {
            GetBarcodeImageJvm()
        }
        single<PermissionService> {
            PermissionServiceJvm()
        }
        single<CacheDirProvider> {
            CacheDirProviderJvm(
                dataDirectory = get(),
            )
        }
        single<PendingUploadDirProvider> {
            PendingUploadDirProviderDesktop(
                dataDirectory = get(),
            )
        }

        single<GetLocale> {
            GetLocaleImpl(
                settingsReadRepository = get(),
            )
        }
        single<PutLocale> {
            PutLocaleImpl(
                settingsReadWriteRepository = get(),
            )
        }
        single<GetPurchased> {
            GetPurchasedImpl()
        }

        single<CleanUpAttachment> {
            CleanUpAttachmentImpl()
        }

        single<DataDirectory> {
            DataDirectory()
        }
        single<DirsService> { get<DataDirectory>() }
        single<ClipboardService> {
            ClipboardServiceJvm(
                getClipboardAutoClear = get(),
                windowCoroutineScope = get(),
            )
        }
        single<ConnectivityService> {
            ConnectivityServiceJvm()
        }
        single<FileWatcherService> {
            FileWatcherServiceJvm()
        }
        single<PowerService> {
            PowerServiceJvm()
        }
        single<TextService> {
            TextServiceImpl(
                fileService = get(),
            )
        }
        single<FileService> {
            FileServiceImpl()
        }
        single<SshAgentStatusService> {
            SshAgentStatusServiceImpl()
        }
        single<GpgAgentStatusService> {
            GpgAgentStatusServiceImpl()
        }
        single<ReviewService> {
            ReviewServiceJvm()
        }
        single<BackupSchedulerWorker> {
            BackupSchedulerWorker(
                sessionReadRepository = get(),
                backupRunService = get(),
                getBackupConfigRepository = get(),
            )
        }
        single<DownloadFileStore> {
            DownloadFileStoreDesktop(
                dataDirectory = get(),
            )
        }
        single<DownloadBackgroundScheduler> {
            DownloadBackgroundSchedulerNoOp
        }
        single<DownloadManager> {
            DownloadManagerImpl(
                windowCoroutineScope = get(),
                downloadRepository = get(),
                sourceLoader = get(),
                downloadFileStore = get(),
                downloadBackgroundScheduler = get(),
                base64Service = get(),
                cryptoGenerator = get(),
            )
        }
        single<DownloadRepository> {
            DownloadRepositoryInMemory()
        }
        single<AutofillService> {
            AutofillServiceAndroid()
        }
        single<SubscriptionService> {
            SubscriptionServiceAndroid()
        }
        single<ClearData> {
            ClearDataAndroid(
                logRepository = get(),
                dataDirectory = get(),
            )
        }
        single<KeyValueStoreFactory> { DesktopKeyValueStoreFactory(get(), get()) }
        single<ExposedDatabaseManager> {
            val dataDirectory: DataDirectory = get()
            val sqlManager = DatabaseSqlManagerInFileJvm<DatabaseExposed>(
                fileIo = dataDirectory
                    .data()
                    .effectMap {
                        File(it, "database_exposed.sqlite")
                    },
            )

            ExposedDatabaseManagerImpl(
                logRepository = get(),
                cryptoGenerator = get(),
                settingsRepository = get(),
                generateMasterKeyUseCase = get(),
                generateMasterHashUseCase = get(),
                generateMasterSaltUseCase = get(),
                json = get(),
                sqlManager = sqlManager,
            )
        }
        single<LogRepositoryKotlin> {
            LogRepositoryKotlin()
        }
    }
}
