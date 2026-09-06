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
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.DownloadRepositoryInMemory
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerNoOp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreDesktop
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
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
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.copy.ClipboardServiceJvm
import com.artemchep.keyguard.copy.ConnectivityServiceJvm
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.FileWatcherServiceJvm
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.PermissionServiceJvm
import com.artemchep.keyguard.copy.PowerServiceJvm
import com.artemchep.keyguard.copy.ReviewServiceJvm
import com.artemchep.keyguard.copy.atomicDataDirectory
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.globalModuleJvm
import com.artemchep.keyguard.feature.biometric.BiometricKeyRepositoryDesktop
import com.artemchep.keyguard.feature.biometric.BiometricPromptHost
import com.artemchep.keyguard.feature.biometric.BiometricPromptHostKeychain
import com.artemchep.keyguard.feature.biometric.BiometricPromptHostWindowsHello
import com.artemchep.keyguard.feature.navigation.defaultNavigationModule
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.factory
import org.kodein.di.instance
import org.kodein.di.multiton
import java.io.File

class BiometricStatusUseCaseImpl(
    private val promptHost: BiometricPromptHost,
) : BiometricStatusUseCase {
    constructor(directDI: DirectDI) : this(
        promptHost = directDI.instance(),
    )

    override fun invoke(): Flow<BiometricStatus> = flow {
        // Only the platforms that have a biometric backend
        // get to probe the native library.
        val hasBiometrics = when (CurrentPlatform) {
            is Platform.Desktop.MacOS,
            is Platform.Desktop.Windows,
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

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        dataDirectory = directDI.instance(),
    )

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
    constructor(directDI: DirectDI) : this(
        dataDirectory = directDI.instance(),
    )

    override suspend fun get(): LocalPath {
        val path = dataDirectory.cache().bind()
        return LocalPath(path)
    }

    override fun getBlocking(): LocalPath {
        val path = dataDirectory.cacheBlocking()
        return LocalPath(path)
    }
}

fun diFingerprintRepositoryModule() = DI.Module(
    name = "com.artemchep.keyguard.core.session.repository::FingerprintRepository",
) {
    import(globalModuleJvm())
    import(defaultNavigationModule())

    bindProvider<LeContext> {
        LeContext()
    }
    bindSingleton<AndroidIpcRegistrationService> {
        AndroidIpcRegistrationServiceNone
    }
    bindSingleton<BackupObjectStoreFactory>(tag = BackupLocalObjectStoreFactoryTag) {
        LocalFolderBackupObjectStoreFactory()
    }
    bindSingleton<BackupObjectStoreFactory> {
        SelectableBackupObjectStoreFactory(
            localFactory = instance(tag = BackupLocalObjectStoreFactoryTag),
            webDavFactory = WebDavBackupObjectStoreFactory(
                httpClient = instance<HttpClient>(),
            ),
        )
    }
    bindSingleton {
        FlavorConfig(
            isFreeAsBeer = false,
        )
    }
    bindSingleton<BiometricStatusUseCase> {
        BiometricStatusUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<BiometricKeyRepository> {
        BiometricKeyRepositoryDesktop(
            directDI = this,
        )
    }
    bindSingleton<BiometricPromptHost> {
        when (CurrentPlatform) {
            is Platform.Desktop.Windows -> BiometricPromptHostWindowsHello(directDI = this)
            else -> BiometricPromptHostKeychain(directDI = this)
        }
    }
    bindSingleton<YubiKeyUnlockAvailability> {
        YubiKeyUnlockAvailability { false }
    }
    bindSingleton<GetBarcodeImage> {
        GetBarcodeImageJvm(
            directDI = this,
        )
    }
    bindSingleton<PermissionService> {
        PermissionServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<CacheDirProvider> {
        CacheDirProviderJvm(
            directDI = this,
        )
    }
    bindSingleton<PendingUploadDirProvider> {
        PendingUploadDirProviderDesktop(
            directDI = this,
        )
    }

    bindSingleton<GetLocale> {
        GetLocaleImpl(
            directDI = this,
        )
    }
    bindSingleton<PutLocale> {
        PutLocaleImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPurchased> {
        GetPurchasedImpl()
    }

    bindSingleton<CleanUpAttachment> {
        CleanUpAttachmentImpl()
    }
//    bindSingleton<CleanUpDownloadImpl> {
//        CleanUpDownloadImpl(
//            directDI = this,
//        )
//    }

    bindSingleton<DataDirectory> {
        DataDirectory(
            directDI = this,
        )
    }
    bindSingleton<ClipboardService> {
        ClipboardServiceJvm(this)
    }
    bindSingleton<ConnectivityService> {
        ConnectivityServiceJvm(this)
    }
    bindSingleton<FileWatcherService> {
        FileWatcherServiceJvm(this)
    }
    bindSingleton<PowerService> {
        PowerServiceJvm(this)
    }
    // TODO: FIX ME!!
//    bindSingleton<LinkInfoExtractorAndroid> {
//        LinkInfoExtractorAndroid(
//            packageManager = instance(),
//        )
//    }
    bindSingleton<TextService> {
        TextServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<FileService> {
        FileServiceImpl()
    }
    bindSingleton<SshAgentStatusService> {
        SshAgentStatusServiceImpl()
    }
    bindSingleton<GpgAgentStatusService> {
        GpgAgentStatusServiceImpl()
    }
    bindSingleton<ReviewService> {
        ReviewServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<Wrker> {
        BackupSchedulerWorker(this)
    }
    bindSingleton<DownloadFileStore> {
        DownloadFileStoreDesktop(
            directDI = this,
        )
    }
    bindSingleton<DownloadBackgroundScheduler> {
        DownloadBackgroundSchedulerNoOp
    }
    bindSingleton<DownloadManager> {
        DownloadManagerImpl(
            directDI = this,
        )
    }
    bindSingleton<DownloadRepository> {
        DownloadRepositoryInMemory()
    }
    bindSingleton<AutofillService> {
        AutofillServiceAndroid()
    }
    bindSingleton<SubscriptionService> {
        SubscriptionServiceAndroid()
    }
    bindSingleton<ClearData> {
        ClearDataAndroid(
            directDI = this,
        )
    }
    bind<KeyValueStore>() with factory { key: Files ->
        val d = instance<DataDirectory>()
        val s = FileJsonKeyValueStoreStore(
            fileIo = d.data().map {
                d.atomicDataDirectory()
                    .resolve(AtomicPathComponent.parse(key.filename))
            },
            json = instance(),
        )
        JsonKeyValueStore(
            s,
        )
    }
    bind<KeyValueStore>("proto") with multiton { file: Files ->
        val m: KeyValueStore = instance(arg = file)
        m
    }
    bind<KeyValueStore>("shared") with multiton { file: Files ->
        val m: KeyValueStore = instance(arg = file)
        m
    }
    bindSingleton<ExposedDatabaseManager> {
        val dataDirectory: DataDirectory = instance()
        val sqlManager = DatabaseSqlManagerInFileJvm<DatabaseExposed>(
            fileIo = dataDirectory
                .data()
                .effectMap {
                    File(it, "database_exposed.sqlite")
                },
        )

        ExposedDatabaseManagerImpl(
            logRepository = instance(),
            cryptoGenerator = instance(),
            settingsRepository = instance(),
            generateMasterKeyUseCase = instance(),
            generateMasterHashUseCase = instance(),
            generateMasterSaltUseCase = instance(),
            json = instance(),
            sqlManager = sqlManager,
        )
    }
    bindSingleton<LogRepositoryKotlin> {
        LogRepositoryKotlin()
    }
}
