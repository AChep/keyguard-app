package com.artemchep.keyguard

import com.artemchep.keyguard.util.fido2.NativeFido2Client

import com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability

import androidx.compose.ui.graphics.ImageBitmap
import com.artemchep.keyguard.apple.billing.GetPurchasedApple
import com.artemchep.keyguard.apple.billing.SubscriptionServiceApple
import com.artemchep.keyguard.apple.billing.appleIsFreeDistribution
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.service.backup.BackupSchedulerWorker
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifier
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.DownloadRepositoryInMemory
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerNoOp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreApple
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandImpl
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.licensekey.EcdsaP256LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.impl.Base32ServiceImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.text.impl.TextServiceImpl
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PutLaunchAtLogin
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.CheckWebDavConnectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.RunBackupNowImpl
import com.artemchep.keyguard.common.usecase.impl.TestBackupLocationImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.copy.ClearDataApple
import com.artemchep.keyguard.copy.ClipboardServiceMacos
import com.artemchep.keyguard.copy.CopyEventsSource
import com.artemchep.keyguard.copy.DateFormatterApple
import com.artemchep.keyguard.copy.DirsServiceMacos
import com.artemchep.keyguard.copy.FileServiceApple
import com.artemchep.keyguard.common.usecase.impl.GetAppBuildDateImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppBuildRefImpl
import com.artemchep.keyguard.copy.NumberFormatterApple
import com.artemchep.keyguard.core.session.usecase.BiometricKeyRepositoryApple
import com.artemchep.keyguard.core.session.usecase.BiometricStatusUseCaseApple
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileApple
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import com.artemchep.keyguard.crypto.NativeGpgKeyExpirationService
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpVerifier
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.ApplePlatformServicesModule
import com.artemchep.keyguard.di.NativeCryptoServicesModule
import com.artemchep.keyguard.platform.AppleBiometricKeychain
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.appleKeyguardAtomicDataDirectory
import com.artemchep.keyguard.platform.appleKeyguardDataDirectory
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.util.io.resolve
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal class MacosPlatformModule {
    val module = module {
        includes(NativeCryptoServicesModule().module)
        includes(AppleBackupModule().module)
        includes(ApplePlatformServicesModule().module)
        single { PlatformLinkInfoExtractorRegistry(emptyList()) }
        single { PlatformLogSinkRegistry(listOf(get<LogRepositoryKotlin>())) }
        single { WorkerRegistry(emptyList()) }

        // Bound by concrete type too: the appearance controller observes the
        // service's copy-events flow (minimize-after-copy) via [CopyEventsSource],
        // which is macOS-specific API (NSPasteboard).
        single<ClipboardServiceMacos> {
            ClipboardServiceMacos(
                getClipboardAutoClear = get(),
                windowCoroutineScope = get(),
            )
        }
        factory<ClipboardService> {
            get<ClipboardServiceMacos>()
        }
        factory<CopyEventsSource> {
            get<ClipboardServiceMacos>()
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
        factory<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>()) {
            Dispatchers.IO
        }
        factory<CoroutineContext>(qualifier = named<DatabaseDispatcher>()) {
            Dispatchers.IO
        }
        single<ClearData> {
            ClearDataApple(
                directories = { listOf(appleKeyguardDataDirectory()) },
                fingerprintRepository = get(),
                getStore = get<AppleKeyValueStoreFactory>()::getJson,
            )
        }
        single<HttpClient> {
            macosHttpClient(
                json = get(),
            )
        }
        single<HttpClient>(qualifier = named("curl")) {
            macosHttpClient(
                json = get(),
            )
        }
        single {
            // Only direct distribution grants premium without a purchase.
            FlavorConfig(isFreeAsBeer = appleIsFreeDistribution())
        }
        single<LeContext> {
            LeContext()
        }
        single<FileService> {
            // The app is sandboxed: files picked by the user are only reachable
            // across relaunches through security-scoped bookmarks, which the
            // apple file service resolves from the access token.
            FileServiceApple()
        }
        single<DirsService> {
            DirsServiceMacos
        }
        single<TextService> {
            TextServiceImpl(
                fileService = get(),
            )
        }
        single { KeychainRepositoryApple() }
        single<KeychainRepository> { get<KeychainRepositoryApple>() }
        single<AppleBiometricKeychain> { get<KeychainRepositoryApple>() }
        single<CacheDirProvider> {
            MacosCacheDirProvider
        }
        single<DownloadRepository> {
            DownloadRepositoryInMemory()
        }
        single<DownloadFileStore> {
            DownloadFileStoreApple
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
        single<AutofillService> {
            MacosNoOpAutofillService
        }
        single<PowerService> {
            MacosPowerService
        }
        single<ReviewService> {
            MacosReviewService
        }
        single<GetBarcodeImage> {
            MacosGetBarcodeImage
        }
        single<CleanUpAttachment> {
            MacosCleanUpAttachment
        }
        single<CheckWebDavConnection> {
            CheckWebDavConnectionImpl(
                httpClient = get(),
            )
        }
        single<LogRepositoryKotlin> {
            LogRepositoryKotlin()
        }
        single<Base64Service> {
            Base64ServiceImpl()
        }
        single<Base32Service> {
            Base32ServiceImpl()
        }
        single<SubscriptionService> {
            SubscriptionServiceApple(
                context = get(),
                windowCoroutineScope = get(),
                cryptoGenerator = get(),
            )
        }
        factory<LicenseClaimSource> {
            get<SubscriptionService>() as SubscriptionServiceApple
        }
        single<LicenseSignatureVerifier> {
            EcdsaP256LicenseSignatureVerifier()
        }
        single<GpgKeyGenerator> {
            NativeGpgKeyGenerator
        }
        single<GpgKeyExpirationService> {
            NativeGpgKeyExpirationService
        }
        single<GpgKeyImportService> {
            NativeGpgKeyImportService
        }
        single<GpgOpenPgpVerifier> {
            NativeGpgOpenPgpVerifier
        }
        single<ExecuteCommand> {
            ExecuteCommandImpl()
        }
        single<GetPurchased> {
            GetPurchasedApple(
                config = get(),
                subscriptionService = get(),
                getDebugPremium = get(),
                getCachePremium = get(),
                putCachePremium = get(),
                getLicensePremium = get(),
                windowCoroutineScope = get(),
            )
        }
        single<NumberFormatter> {
            NumberFormatterApple()
        }
        single<DateFormatter> {
            DateFormatterApple(
                context = get(),
            )
        }
        single<BiometricStatusUseCase> {
            BiometricStatusUseCaseApple(
                base64Service = get(),
                cryptoGenerator = get(),
                biometricKeychain = get(),
            )
        }
        single<BiometricKeyRepository> {
            BiometricKeyRepositoryApple(
                keychainRepository = get(),
            )
        }
        single<Fido2UnlockAvailability> {
            Fido2UnlockAvailability { NativeFido2Client().isSupported }
        }
        single<YubiKeyUnlockAvailability> {
            // macOS performs the HMAC-SHA1 challenge-response over USB HID
            // through the shared util/yubikey Rust backend.
            YubiKeyUnlockAvailability { true }
        }
        single<GpgAgentCrypto> {
            NativeGpgAgentCrypto
        }
        single<GpgAgentStatusService> {
            GpgAgentStatusServiceImpl()
        }
        single<GetLaunchAtLogin> {
            GetLaunchAtLoginMacos
        }
        single<PutLaunchAtLogin> {
            PutLaunchAtLoginMacos
        }

        single<RunBackupNow> {
            RunBackupNowImpl(
                backupRunService = get(),
            )
        }
        single<TestBackupLocation> {
            TestBackupLocationImpl(
                backupObjectStoreFactory = get(),
            )
        }
        single<BackupSchedulerWorker> {
            BackupSchedulerWorker(
                sessionReadRepository = get(),
                backupRunService = get(),
                getBackupConfigRepository = get(),
            )
        }
        single<SshAgentStatusService> {
            SshAgentStatusServiceImpl()
        }

        single<GetAppBuildDate> {
            GetAppBuildDateImpl(
                formatter = get(),
            )
        }
        single<GetAppBuildRef> {
            GetAppBuildRefImpl()
        }

        // One live store per file, shared by the repositories and by erasure so a
        // ClearData run resets the preferences already held in memory.
        single { AppleKeyValueStoreFactory(get(), appleKeyguardAtomicDataDirectory()) }
        single<KeyValueStoreFactory> { get<AppleKeyValueStoreFactory>() }
        single<AndroidIpcRegistrationService> { AndroidIpcRegistrationServiceNone }
        single<ExposedDatabaseManager> {
            val sqlManager = DatabaseSqlManagerInFileApple<DatabaseExposed>(
                directory = appleKeyguardDataDirectory().resolve("exposed"),
                fileName = "database_exposed.sqlite",
                onCreate = { _: DatabaseExposed ->
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
    }
}

private fun macosHttpClient(
    json: Json,
) = HttpClient(Darwin) {
    install(UserAgent) {
        agent = BitwardenPersona.of(CurrentPlatform)
            .userAgent
    }
    install(ContentNegotiation) {
        register(ContentType.Application.Json, KotlinxSerializationConverter(json))
    }
    install(WebSockets) {
        pingIntervalMillis = HTTP_PING_INTERVAL_MS
    }
    install(HttpCache) {
        // In memory.
    }
    install(HttpRequestRetry) {
        configureBitwardenHttpRetry()
    }
}

private const val HTTP_PING_INTERVAL_MS = 20_000L

private object MacosCacheDirProvider : CacheDirProvider {
    override suspend fun get(): LocalPath = cacheDir()

    override fun getBlocking(): LocalPath = cacheDir()

    private fun cacheDir(): LocalPath = appleKeyguardDataDirectory()
        .resolve("cache")
}

private object MacosNoOpAutofillService : AutofillService {
    override fun status(): Flow<AutofillServiceStatus> =
        flowOf(AutofillServiceStatus.Disabled(onEnable = null))
}

private object MacosPowerService : PowerService {
    override fun getScreenState(): Flow<Screen> = flowOf(Screen.On)
}

private object MacosReviewService : ReviewService {
    override fun request(context: LeContext) = ioUnit()
}

private object MacosGetBarcodeImage : GetBarcodeImage {
    override fun invoke(request: BarcodeImageRequest) = ioEffect {
        val width = request.size?.width ?: 1
        val height = request.size?.height ?: 1
        ImageBitmap(width, height)
    }
}

private object MacosCleanUpAttachment : CleanUpAttachment {
    override fun invoke() = io(0)
}
