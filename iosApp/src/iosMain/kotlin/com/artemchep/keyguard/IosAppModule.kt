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
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.CacheDirProviderIos
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadRepositoryInMemory
import com.artemchep.keyguard.common.service.download.DownloadTaskIos
import com.artemchep.keyguard.common.service.download.DownloadManagerImpl
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundScheduler
import com.artemchep.keyguard.common.service.download.scheduler.DownloadBackgroundSchedulerNoOp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreIos
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandImpl
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keychain.impl.KeychainRepositoryNoOp
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
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
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.copy.AutofillServiceIos
import com.artemchep.keyguard.copy.Base32ServiceIos
import com.artemchep.keyguard.copy.BiometricStatusUseCaseIos
import com.artemchep.keyguard.copy.ClipboardServiceIos
import com.artemchep.keyguard.copy.CleanUpAttachmentIos
import com.artemchep.keyguard.copy.ClearDataIos
import com.artemchep.keyguard.copy.DateFormatterIos
import com.artemchep.keyguard.copy.DirsServiceIos
import com.artemchep.keyguard.copy.FileServiceIos
import com.artemchep.keyguard.copy.GetAppBuildDateIos
import com.artemchep.keyguard.copy.GetAppBuildRefIos
import com.artemchep.keyguard.copy.GetBarcodeImageIos
import com.artemchep.keyguard.copy.GetPasswordStrengthIos
import com.artemchep.keyguard.copy.GetPurchasedIos
import com.artemchep.keyguard.copy.NumberFormatterIos
import com.artemchep.keyguard.copy.PermissionServiceIos
import com.artemchep.keyguard.copy.PowerServiceIos
import com.artemchep.keyguard.copy.ReviewServiceIos
import com.artemchep.keyguard.copy.SubscriptionServiceIos
import com.artemchep.keyguard.copy.ZipServiceIos
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileIos
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.CipherEncryptorIos
import com.artemchep.keyguard.crypto.CryptoGeneratorIos
import com.artemchep.keyguard.crypto.FileEncryptorIos
import com.artemchep.keyguard.crypto.KeyPairGeneratorIos
import com.artemchep.keyguard.crypto.SshKeyImportServiceIos
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.globalModuleCommon
import com.artemchep.keyguard.feature.navigation.defaultNavigationModule
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadServiceIos
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProviderIos
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.Sink
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.factory
import org.kodein.di.instance
import org.kodein.di.multiton

internal fun DI.Builder.installIosAppModule(
) {
    import(defaultNavigationModule())
    import(globalModuleCommon())

    bindSingleton<ClipboardService> {
        ClipboardServiceIos(this)
    }
    bindSingleton<GetLocale> {
        GetLocaleImpl(this)
    }
    bindSingleton<PutLocale> {
        PutLocaleImpl(this)
    }
    bindProvider<CoroutineDispatcher>(tag = DatabaseDispatcher) {
        Dispatchers.IO
    }
    bindProvider<CoroutineContext>(tag = DatabaseDispatcher) {
        Dispatchers.IO
    }
    bindSingleton<ClearData> {
        ClearDataIos
    }
    bindSingleton<Json> {
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
    bindSingleton<HttpClient> {
        iosHttpClient(
            json = instance(),
        )
    }
    bindSingleton<HttpClient>(tag = "curl") {
        iosHttpClient(
            json = instance(),
        )
    }
    bindSingleton {
        FlavorConfig(isFreeAsBeer = true)
    }
    bindSingleton<LeContext> {
        LeContext()
    }
    bindSingleton<FileService> {
        FileServiceIos()
    }
    bindSingleton<DirsService> {
        DirsServiceIos
    }
    bindSingleton<TextService> {
        TextServiceImpl(this)
    }
    bindSingleton<KeychainRepository> {
        KeychainRepositoryNoOp(this)
    }
    bindSingleton<CacheDirProvider> {
        CacheDirProviderIos
    }
    bindSingleton<PendingUploadDirProvider> {
        PendingUploadDirProviderIos
    }
    bindSingleton<DownloadRepository> {
        DownloadRepositoryInMemory()
    }
    bindSingleton<DownloadFileStore> {
        DownloadFileStoreIos(this)
    }
    bindSingleton<DownloadBackgroundScheduler> {
        DownloadBackgroundSchedulerNoOp
    }
    bindSingleton<DownloadManager> {
        DownloadManagerImpl(this)
    }
    bindSingleton<DownloadTask> {
        DownloadTaskIos(this)
    }
    bindSingleton<AutofillService> {
        AutofillServiceIos
    }
    bindSingleton<PowerService> {
        PowerServiceIos
    }
    bindSingleton<ReviewService> {
        ReviewServiceIos
    }
    bindSingleton<NotificationRepository> {
        NotificationRepositoryIos
    }
    bindSingleton<GetBarcodeImage> {
        GetBarcodeImageIos
    }
    bindSingleton<CleanUpAttachment> {
        CleanUpAttachmentIos
    }
    bindSingleton<LogRepositoryKotlin> {
        LogRepositoryKotlin()
    }
    bindSingleton<Base64Service> {
        Base64ServiceImpl()
    }
    bindSingleton<Base32Service> {
        Base32ServiceIos
    }
    bindSingleton<SubscriptionService> {
        SubscriptionServiceIos
    }
    bindSingleton<CryptoGenerator> {
        CryptoGeneratorIos()
    }
    bindSingleton<LicenseSignatureVerifier> {
        TODO("Implement license signature verifier")
    }
    bindSingleton<KeyPairGenerator> {
        KeyPairGeneratorIos
    }
    bindSingleton<PermissionService> {
        PermissionServiceIos
    }
    bindSingleton<CipherEncryptor> {
        CipherEncryptorIos(this)
    }
    bindSingleton<FileEncryptor> {
        FileEncryptorIos(this)
    }
    bindSingleton<SshKeyImportService> {
        SshKeyImportServiceIos
    }
    bindSingleton<EncryptedFilePendingUploadService> {
        EncryptedFilePendingUploadServiceIos
    }
    bindSingleton<ZipService> {
        ZipServiceIos
    }
    bindSingleton<ExecuteCommand> {
        ExecuteCommandImpl(this)
    }
    bindSingleton<GetPurchased> {
        GetPurchasedIos
    }
    bindSingleton<GetPasswordStrength> {
        GetPasswordStrengthIos
    }
    bindSingleton<NumberFormatter> {
        NumberFormatterIos(this)
    }
    bindSingleton<DateFormatter> {
        DateFormatterIos
    }
    bindSingleton<BiometricStatusUseCase> {
        BiometricStatusUseCaseIos
    }
    bindSingleton<YubiKeyUnlockAvailability> {
        YubiKeyUnlockAvailability { false }
    }

    bindSingleton<SshAgentStatusService> {
        SshAgentStatusServiceImpl()
    }

    bindSingleton<GetAppBuildDate> {
        GetAppBuildDateIos
    }
    bindSingleton<GetAppBuildRef> {
        GetAppBuildRefIos
    }

    bind<KeyValueStore>() with factory { key: Files ->
        val file = iosKeyguardDataDirectory()
            .resolve("keyvalue", key.filename)
        JsonKeyValueStore(
            FileJsonKeyValueStoreStore(
                fileIo = io(file),
                json = instance(),
            ),
        )
    }
    bind<KeyValueStore>("proto") with multiton { file: Files ->
        val store: KeyValueStore = instance(arg = file)
        store
    }
    bind<KeyValueStore>("shared") with multiton { file: Files ->
        val store: KeyValueStore = instance(arg = file)
        store
    }
    bindSingleton<ExposedDatabaseManager> {
        val sqlManager = DatabaseSqlManagerInFileIos<DatabaseExposed>(
            directory = iosKeyguardDataDirectory().resolve("exposed"),
            fileName = "database_exposed.sqlite",
            onCreate = { _: DatabaseExposed ->
                ioUnit()
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
