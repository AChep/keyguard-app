package com.artemchep.keyguard

import androidx.compose.ui.graphics.ImageBitmap
import arrow.core.Either
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.autofill.AutofillService
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
import com.artemchep.keyguard.common.service.download.CacheDirProviderIos
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.DownloadRepositoryInMemory
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerNoOp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreIos
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandImpl
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keychain.impl.KeychainRepositoryNoOp
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.notification.impl.NotificationRepositoryIos
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.service.sshagent.impl.SshAgentStatusServiceImpl
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.text.impl.TextServiceImpl
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.copy.AutofillServiceIos
import com.artemchep.keyguard.copy.Base32ServiceIos
import com.artemchep.keyguard.copy.BiometricStatusUseCaseIos
import com.artemchep.keyguard.copy.CleanUpAttachmentIos
import com.artemchep.keyguard.copy.ClearDataIos
import com.artemchep.keyguard.copy.ClipboardServiceIos
import com.artemchep.keyguard.copy.DateFormatterIos
import com.artemchep.keyguard.copy.DirsServiceIos
import com.artemchep.keyguard.copy.FileServiceIos
import com.artemchep.keyguard.copy.GetAppBuildDateIos
import com.artemchep.keyguard.copy.GetAppBuildRefIos
import com.artemchep.keyguard.copy.GetBarcodeImageIos
import com.artemchep.keyguard.copy.GetPurchasedIos
import com.artemchep.keyguard.copy.NumberFormatterApple
import com.artemchep.keyguard.copy.PermissionServiceIos
import com.artemchep.keyguard.copy.PowerServiceIos
import com.artemchep.keyguard.copy.ReviewServiceIos
import com.artemchep.keyguard.copy.SubscriptionServiceIos
import com.artemchep.keyguard.core.session.usecase.BiometricKeyRepositoryApple
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileApple
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.NativeGpgKeyExpirationService
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpVerifier
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.NativeCryptoServicesModule
import com.artemchep.keyguard.feature.navigation.NavigationModule
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.iosKeyguardAtomicDataDirectory
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadServiceIos
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProviderIos
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
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
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Sink
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal class IosPlatformModule {
    val module = module {
        includes(NativeCryptoServicesModule().module)
        single { PlatformLinkInfoExtractorRegistry(emptyList()) }
        single { PlatformLogSinkRegistry(listOf(get<LogRepositoryKotlin>())) }
        single { WorkerRegistry(emptyList()) }

        single<ClipboardService> {
            ClipboardServiceIos(
                getClipboardAutoClear = get(),
                windowCoroutineScope = get(),
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
        factory<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>()) {
            Dispatchers.IO
        }
        factory<CoroutineContext>(qualifier = named<DatabaseDispatcher>()) {
            Dispatchers.IO
        }
        single<ClearData> {
            ClearDataIos
        }
        single<Json> {
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                prettyPrint = false
                isLenient = true
                serializersModule = SerializersModule {
                    polymorphic(BitwardenCipher.Attachment::class) {
                        subclass(BitwardenCipher.Attachment.Remote::class)
                        subclass(BitwardenCipher.Attachment.Local::class)
                        defaultDeserializer { BitwardenCipher.Attachment.Remote.serializer() }
                    }
                    polymorphic(ServiceToken::class) {
                        subclass(BitwardenToken::class)
                        subclass(KeePassToken::class)
                        defaultDeserializer { BitwardenToken.serializer() }
                    }
                    polymorphic(PrivilegedAppListEntity.App::class) {
                        subclass(PrivilegedAppListEntity.App.AndroidApp::class)
                        subclass(PrivilegedAppListEntity.App.Unknown::class)
                        defaultDeserializer { PrivilegedAppListEntity.App.Unknown.serializer() }
                    }
                }
            }
        }
        single<HttpClient> {
            iosHttpClient(
                json = get(),
            )
        }
        single<HttpClient>(qualifier = named("curl")) {
            iosHttpClient(
                json = get(),
            )
        }
        single {
            FlavorConfig(isFreeAsBeer = true)
        }
        single<LeContext> {
            LeContext()
        }
        single<FileService> {
            FileServiceIos()
        }
        single<DirsService> {
            DirsServiceIos
        }
        single<TextService> {
            TextServiceImpl(
                fileService = get(),
            )
        }
        single<KeychainRepository> {
            KeychainRepositoryNoOp()
        }
        single<CacheDirProvider> {
            CacheDirProviderIos
        }
        single<PendingUploadDirProvider> {
            PendingUploadDirProviderIos
        }
        single<DownloadRepository> {
            DownloadRepositoryInMemory()
        }
        single<DownloadFileStore> {
            DownloadFileStoreIos
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
            AutofillServiceIos
        }
        single<PowerService> {
            PowerServiceIos
        }
        single<ReviewService> {
            ReviewServiceIos
        }
        single<NotificationRepository> {
            NotificationRepositoryIos
        }
        single<GetBarcodeImage> {
            GetBarcodeImageIos
        }
        single<CleanUpAttachment> {
            CleanUpAttachmentIos
        }
        single<LogRepositoryKotlin> {
            LogRepositoryKotlin()
        }
        single<Base64Service> {
            Base64ServiceImpl()
        }
        single<Base32Service> {
            Base32ServiceIos
        }
        single<SubscriptionService> {
            SubscriptionServiceIos
        }
        single<LicenseSignatureVerifier> {
            TODO("Implement license signature verifier")
        }
        single<PermissionService> {
            PermissionServiceIos
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
        single<EncryptedFilePendingUploadService> {
            EncryptedFilePendingUploadServiceIos
        }
        single<ExecuteCommand> {
            ExecuteCommandImpl()
        }
        single<GetPurchased> {
            GetPurchasedIos
        }
        single<NumberFormatter> {
            NumberFormatterApple()
        }
        single<DateFormatter> {
            DateFormatterIos
        }
        single<BiometricStatusUseCase> {
            BiometricStatusUseCaseIos
        }
        single<BiometricKeyRepository> {
            BiometricKeyRepositoryApple(
                keychainRepository = get(),
            )
        }
        single<YubiKeyUnlockAvailability> {
            YubiKeyUnlockAvailability { false }
        }

        single<SshAgentStatusService> {
            SshAgentStatusServiceImpl()
        }

        single<GetAppBuildDate> {
            GetAppBuildDateIos
        }
        single<GetAppBuildRef> {
            GetAppBuildRefIos
        }

        single<KeyValueStoreFactory> { IosKeyValueStoreFactory(get()) }
        single<AndroidIpcRegistrationService> { AndroidIpcRegistrationServiceNone }
        single<ExposedDatabaseManager> {
            val sqlManager = DatabaseSqlManagerInFileApple<DatabaseExposed>(
                directory = iosKeyguardDataDirectory().resolve("exposed"),
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

private fun iosHttpClient(
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
        pingIntervalMillis = 20_000
    }
    install(HttpCache) {
        // In memory.
    }
    install(HttpRequestRetry) {
    }
}
