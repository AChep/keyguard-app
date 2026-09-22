package com.artemchep.keyguard.core.session

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeImportTransportAndroid
import com.artemchep.keyguard.android.downloader.journal.DownloadRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.room.DownloadDatabaseManager
import com.artemchep.keyguard.android.notiifcation.NotificationRepositoryAndroid
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.backup.AndroidTreeBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupLocalObjectStoreFactoryTag
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.SelectableBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.WebDavBackupObjectStoreFactory
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransport
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerAndroid
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreAndroid
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentStatusServiceStatelessProxy
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keychain.impl.KeychainRepositoryNoOp
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentStatusServiceStatelessProxy
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.GetPurchasedImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.copy.AndroidLinkInfoExtractorRegistry
import com.artemchep.keyguard.copy.AutofillServiceAndroid
import com.artemchep.keyguard.copy.ClearDataAndroid
import com.artemchep.keyguard.copy.ClipboardServiceAndroid
import com.artemchep.keyguard.copy.ConnectivityServiceAndroid
import com.artemchep.keyguard.copy.DirsServiceAndroid
import com.artemchep.keyguard.copy.FileServiceAndroid
import com.artemchep.keyguard.copy.FileWatcherServiceAndroid
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.LinkInfoExtractorAndroid
import com.artemchep.keyguard.copy.LinkInfoExtractorLaunch
import com.artemchep.keyguard.copy.LogRepositoryAndroid
import com.artemchep.keyguard.copy.PermissionServiceAndroid
import com.artemchep.keyguard.copy.PowerServiceAndroid
import com.artemchep.keyguard.copy.ReviewServiceAndroid
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactory
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryV1
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryV2
import com.artemchep.keyguard.copy.SubscriptionServiceAndroid
import com.artemchep.keyguard.copy.TextServiceAndroid
import com.artemchep.keyguard.core.session.usecase.BiometricKeyRepositoryAndroid
import com.artemchep.keyguard.core.session.usecase.BiometricStatusUseCaseImpl
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileAndroid
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.GlobalModuleJvm
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthCoordinatorAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthSecurityAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthTransportAndroid
import com.artemchep.keyguard.feature.navigation.NavigationModule
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProviderAndroid
import com.artemchep.keyguard.util.io.toLocalPath
import db_key_value.datastore.DataStoreKeyValueStore
import db_key_value.datastore.encrypted.SecureDataStoreKeyValueStore
import db_key_value.datastore.encrypted.SecureStorageCoordinator
import db_key_value.shared_prefs.SharedPrefsKeyValueStore
import db_key_value.shared_prefs.encrypted.SecureSharedPrefsKeyValueStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

class CacheDirProviderAndroid(
    private val context: Context,
) : CacheDirProvider {

    override suspend fun get(): LocalPath = withContext(Dispatchers.IO) {
        getBlocking()
    }

    override fun getBlocking(): LocalPath = context.cacheDir.toLocalPath()
}

class PlatformApplicationModule {
    val module = module {
        includes(GlobalModuleJvm().module)
        single {
            PlatformLinkInfoExtractorRegistry(
                listOf(
                    get<LinkInfoExtractorAndroid>(),
                    get<LinkInfoExtractorLaunch>(),
                ),
            )
        }
        single { PlatformLogSinkRegistry(listOf(get<LogRepositoryAndroid>())) }
        single { AndroidLinkInfoExtractorRegistry(listOf(get<LinkInfoExtractorAndroid>())) }

        factory<LeContext>() {
            val context: Context = get()
            LeContext(context)
        }
        single<BackupObjectStoreFactory>(qualifier = named(BackupLocalObjectStoreFactoryTag)) {
            AndroidTreeBackupObjectStoreFactory(
                context = get(),
            )
        }
        single<BackupObjectStoreFactory> {
            SelectableBackupObjectStoreFactory(
                localFactory = get(qualifier = named(BackupLocalObjectStoreFactoryTag)),
                webDavFactory = WebDavBackupObjectStoreFactory(
                    httpClient = get<HttpClient>(),
                ),
            )
        }
        single<BiometricStatusUseCase> {
            BiometricStatusUseCaseImpl(get())
        }
        single<BiometricKeyRepository> {
            BiometricKeyRepositoryAndroid()
        }
        single<YubiKeyUnlockAvailability> {
            YubiKeyUnlockAvailability { true }
        }
        single<KeychainRepository> {
            KeychainRepositoryNoOp()
        }
        single<NotificationRepository> {
            NotificationRepositoryAndroid(
                context = get(),
            )
        }
        single<GetBarcodeImage> {
            GetBarcodeImageJvm()
        }

        single<CacheDirProvider> {
            CacheDirProviderAndroid(
                context = get(),
            )
        }
        single<PendingUploadDirProvider> {
            PendingUploadDirProviderAndroid(
                context = get(),
            )
        }
        single<GetPurchased> {
            GetPurchasedImpl(
                context = get(),
                config = get(),
                subscriptionService = get(),
                getLicensePremium = get(),
                getDebugPremium = get(),
                getCachePremium = get(),
                putCachePremium = get(),
                windowCoroutineScope = get(),
            )
        }

        single<CleanUpAttachment> {
            CleanUpAttachmentImpl(
                context = get<Application>(),
                logRepository = get(),
                downloadRepository = get(),
            )
        }
        single<ClipboardService> {
            ClipboardServiceAndroid(
                application = get<Application>(),
                clipboardManager = get<Application>().getSystemService<ClipboardManager>()!!,
                windowCoroutineScope = get(),
                getClipboardAutoClear = get(),
            )
        }
        single<CompanionAuthTransportAndroid> {
            CompanionAuthTransportAndroid(
                application = get(),
                fileService = get(),
            )
        }
        single<CompanionAuthSecurityAndroid> {
            CompanionAuthSecurityAndroid(
                application = get(),
                json = get(),
                cryptoGenerator = get(),
                cipherEncryptor = get(),
                base64Service = get(),
            )
        }
        single<CompanionAuthCoordinatorAndroid> {
            CompanionAuthCoordinatorAndroid(
                application = get(),
                json = get(),
                transport = get(),
                security = get(),
                getVaultSession = get(),
            )
        }
        single<CompanionAuthBridgeAndroid> {
            CompanionAuthBridgeAndroid(
                json = get(),
                coordinator = get(),
            )
        }
        single<ConnectivityService> {
            ConnectivityServiceAndroid(
                context = get<Application>(),
            )
        }
        single<CredentialExchangeImportTransport> {
            CredentialExchangeImportTransportAndroid(
                logRepository = get(),
            )
        }
        single<DirsService> {
            DirsServiceAndroid(
                context = get<Application>(),
            )
        }
        single<FileWatcherService> {
            FileWatcherServiceAndroid(
                context = get<Application>(),
            )
        }
        single<PowerService> {
            PowerServiceAndroid(
                application = get<Application>(),
            )
        }
        single {
            PermissionServiceAndroid(
                context = get<Application>(),
            )
        }
        factory<PermissionService> {
            get<PermissionServiceAndroid>()
        }
        single<LinkInfoExtractorAndroid> {
            LinkInfoExtractorAndroid(
                packageManager = get(),
            )
        }
        single<LinkInfoExtractorLaunch> {
            LinkInfoExtractorLaunch(
                packageManager = get(),
            )
        }
        single<TextService> {
            TextServiceAndroid(
                fileService = get(),
            )
        }
        single<FileService> {
            FileServiceAndroid(
                context = get<Application>(),
            )
        }
        single<SshAgentStatusService> {
            SshAgentStatusServiceStatelessProxy(
                getSshAgent = get(),
            )
        }
        single<GpgAgentStatusService> {
            GpgAgentStatusServiceStatelessProxy(
                getGpgAgent = get(),
            )
        }
        single<ReviewService> {
            ReviewServiceAndroid(
                context = get<Application>(),
            )
        }
        factory<PackageManager> {
            get<Application>().packageManager
        }
        single<DownloadFileStore> {
            DownloadFileStoreAndroid(
                context = get<Application>(),
            )
        }
        single<DownloadBackgroundScheduler> {
            DownloadBackgroundSchedulerAndroid(
                context = get<Application>(),
            )
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
            DownloadRepositoryImpl(
                databaseManager = get(),
            )
        }
        single<AutofillService> {
            AutofillServiceAndroid(
                application = get(),
                showMessage = get(),
            )
        }
        single<SubscriptionServiceAndroid> {
            SubscriptionServiceAndroid(
                context = get(),
                billingManager = get(),
                cryptoGenerator = get(),
            )
        }
        factory<SubscriptionService> {
            get<SubscriptionServiceAndroid>()
        }
        factory<LicenseClaimSource> {
            get<SubscriptionServiceAndroid>()
        }
        single<ClearData> {
            ClearDataAndroid(get())
        }
        single { SharedPreferencesStoreFactoryV1(context = get(), logRepository = get()) }
        single { SharedPreferencesStoreFactoryV2(get(), get(), get(), get()) }
        single<KeyValueStoreFactory> { get<SharedPreferencesStoreFactoryV2>() }
        single<SecureStorageCoordinator> {
            SecureStorageCoordinator.create(
                context = get<Application>(),
                logRepository = get(),
            )
        }
        single<DownloadDatabaseManager> {
            DownloadDatabaseManager(
                applicationContext = get<Application>(),
                name = "download",
                deviceEncryptionKeyUseCase = get(),
            )
        }
        single<ExposedDatabaseManager> {
            val sqlManager = DatabaseSqlManagerInFileAndroid<DatabaseExposed>(
                context = get<Application>(),
                fileName = "database_exposed",
                onCreate = { database ->
                    ioUnit()
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
        single<LogRepositoryAndroid> {
            LogRepositoryAndroid()
        }
    }
}
