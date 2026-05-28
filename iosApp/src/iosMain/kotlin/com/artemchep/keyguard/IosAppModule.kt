package com.artemchep.keyguard

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import arrow.core.Either
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toSource
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.WithBiometric
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandImpl
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.PureFileService
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keychain.impl.KeychainRepositoryNoOp
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributor
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
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
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.common.usecase.impl.*
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.copy.ClipboardServiceIos
import com.artemchep.keyguard.copy.NumberFormatterIos
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileIos
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.CipherEncryptorIos
import com.artemchep.keyguard.crypto.CryptoGeneratorIos
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.globalModuleCommon
import com.artemchep.keyguard.feature.navigation.defaultNavigationModule
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirProvider
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.compose.resources.ExperimentalResourceApi
import com.artemchep.keyguard.res.Res
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext
import platform.Foundation.NSFileManager
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
        IosClearData
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
        PureFileService()
    }
    bindSingleton<DirsService> {
        IosNoOpDirsService
    }
    bindSingleton<TextService> {
        TextServiceImpl(this)
    }
    bindSingleton<KeychainRepository> {
        KeychainRepositoryNoOp(this)
    }
    bindSingleton<CacheDirProvider> {
        IosCacheDirProvider
    }
    bindSingleton<PendingUploadDirProvider> {
        IosPendingUploadDirProvider
    }
    bindSingleton<DownloadRepository> {
        IosDownloadRepository
    }
    bindSingleton<DownloadManager> {
        IosDownloadManager
    }
    bindSingleton<DownloadTask> {
        IosDownloadTask
    }
    bindSingleton<AutofillService> {
        IosNoOpAutofillService
    }
    bindSingleton<PowerService> {
        IosPowerService
    }
    bindSingleton<ReviewService> {
        IosReviewService
    }
    bindSingleton<NotificationRepository> {
        IosNoOpNotificationRepository
    }
    bindSingleton<GetBarcodeImage> {
        IosGetBarcodeImage
    }
    bindSingleton<CleanUpAttachment> {
        IosCleanUpAttachment
    }
    bindSingleton<LogRepositoryKotlin> {
        LogRepositoryKotlin()
    }
    bindSingleton<Base64Service> {
        Base64ServiceImpl()
    }
    bindSingleton<Base32Service> {
        IosBase32Service
    }
    bindSingleton<SubscriptionService> {
        IosSubscriptionService
    }
    bindSingleton<CryptoGenerator> {
        CryptoGeneratorIos()
    }
    bindSingleton<KeyPairGenerator> {
        IosUnsupportedKeyPairGenerator
    }
    bindSingleton<PermissionService> {
        IosPermissionService
    }
    bindSingleton<CipherEncryptor> {
        CipherEncryptorIos(this)
    }
    bindSingleton<SshKeyImportService> {
        IosUnsupportedSshKeyImportService
    }
    bindSingleton<EncryptedFilePendingUploadService> {
        IosUnsupportedEncryptedFilePendingUploadService
    }
    bindSingleton<ZipService> {
        IosUnsupportedZipService
    }
    bindSingleton<ExecuteCommand> {
        ExecuteCommandImpl(this)
    }
    bindSingleton<GetPurchased> {
        IosGetPurchased
    }
    bindSingleton<GetPasswordStrength> {
        IosGetPasswordStrength
    }
    bindSingleton<NumberFormatter> {
        NumberFormatterIos(this)
    }
    bindSingleton<DateFormatter> {
        IosDateFormatter
    }
    bindSingleton<BiometricStatusUseCase> {
        IosBiometricStatusUseCase
    }
    bindSingleton<YubiKeyUnlockAvailability> {
        YubiKeyUnlockAvailability { false }
    }

    bindSingleton<SshAgentStatusService> {
        SshAgentStatusServiceImpl()
    }

    bindSingleton<GetAppBuildDate> {
        IosGetAppBuildDate
    }
    bindSingleton<GetAppBuildRef> {
        IosGetAppBuildRef
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

private object IosNoOpDirsService : DirsService {
    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ) = io<String?>(null)
}

private object IosCacheDirProvider : CacheDirProvider {
    override suspend fun get(): LocalPath = cacheDir()

    override fun getBlocking(): LocalPath = cacheDir()

    private fun cacheDir(): LocalPath = iosKeyguardDataDirectory()
        .resolve("cache")
}

private object IosPendingUploadDirProvider : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): LocalPath = iosKeyguardDataDirectory()
        .resolve("pending_uploads", accountId, namespace)
}

private object IosDownloadRepository : DownloadRepository {
    override fun get(): Flow<List<DownloadInfoEntity2>> = flowOf(emptyList())

    override fun put(model: DownloadInfoEntity2) = ioUnit()

    override fun getById(id: String) = io<DownloadInfoEntity2?>(null)

    override fun getByIdFlow(id: String): Flow<DownloadInfoEntity2?> = flowOf(null)

    override fun getByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ) = io<DownloadInfoEntity2?>(null)

    override fun removeById(id: String) = ioUnit()

    override fun removeByTag(tag: DownloadInfoEntity2.AttachmentDownloadTag) = ioUnit()
}

private object IosDownloadManager : DownloadManager {
    override fun statusByDownloadId2(downloadId: String): Flow<DownloadProgress> =
        flowOf(DownloadProgress.None)

    override fun statusByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): Flow<DownloadProgress> = flowOf(DownloadProgress.None)

    override suspend fun queue(
        downloadInfo: DownloadInfoEntity2,
    ): DownloadManager.QueueResult = throw unsupportedIosFeature("Downloads")

    override suspend fun queue(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
        url: String,
        urlIsOneTime: Boolean,
        name: String,
        data: ByteArray?,
        key: ByteArray?,
        attempt: Int,
        worker: Boolean,
    ): DownloadManager.QueueResult = throw unsupportedIosFeature("Downloads")

    override suspend fun removeByDownloadId(downloadId: String) {
    }

    override suspend fun removeByTag(tag: DownloadInfoEntity2.AttachmentDownloadTag) {
    }
}

private object IosDownloadTask : DownloadTask {
    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = unsupportedDownloadFlow()

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = unsupportedDownloadFlow()

    private fun unsupportedDownloadFlow(): Flow<DownloadProgress> =
        flowOf(DownloadProgress.Complete(Either.Left(unsupportedIosFeature("Downloads"))))
}

private object IosNoOpAutofillService : AutofillService {
    override fun status(): Flow<AutofillServiceStatus> =
        flowOf(AutofillServiceStatus.Disabled(onEnable = null))
}

private object IosPowerService : PowerService {
    override fun getScreenState(): Flow<Screen> = flowOf(Screen.On)
}

private object IosReviewService : ReviewService {
    override fun request(context: LeContext) = ioUnit()
}

private object IosNoOpNotificationRepository : NotificationRepository {
    override fun post(notification: DNotification) = io<DNotificationKey?>(null)

    override fun delete(key: DNotificationKey) = ioUnit()
}

private object IosGetBarcodeImage : GetBarcodeImage {
    override fun invoke(request: BarcodeImageRequest) = ioEffect {
        val width = request.size?.width ?: 1
        val height = request.size?.height ?: 1
        ImageBitmap(width, height)
    }
}

private object IosCleanUpAttachment : CleanUpAttachment {
    override fun invoke() = io(0)
}

private object IosUnsupportedSshKeyImportService : SshKeyImportService {
    override fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult = SshKeyImportResult.Error(
        reason = SshKeyImportError.UnsupportedFormat,
    )
}

private object IosUnsupportedEncryptedFilePendingUploadService : EncryptedFilePendingUploadService {
    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = throw unsupportedIosFeature("Pending uploads")

    override suspend fun markUploaded(pendingUpload: PendingUploadFile) {
    }

    override suspend fun isUploaded(pendingUpload: PendingUploadFile): Boolean = false

    override suspend fun delete(pendingUpload: PendingUploadFile) {
    }
}

private object IosUnsupportedZipService : ZipService {
    override suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        throw unsupportedIosFeature("ZIP export")
    }
}

private object IosBase32Service : Base32Service {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    override fun encode(bytes: ByteArray): ByteArray {
        val output = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                output.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
            buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
        }
        if (bitsLeft > 0) {
            output.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        while (output.length % 8 != 0) {
            output.append('=')
        }
        return output.toString().encodeToByteArray()
    }

    override fun decode(bytes: ByteArray): ByteArray {
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        bytes.decodeToString()
            .uppercase()
            .asSequence()
            .filterNot { it == '=' || it.isWhitespace() || it == '-' }
            .forEach { char ->
                val value = ALPHABET.indexOf(char)
                require(value >= 0) {
                    "Invalid Base32 character: $char"
                }
                buffer = (buffer shl 5) or value
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    output += ((buffer shr bitsLeft) and 0xff).toByte()
                    buffer = if (bitsLeft > 0) buffer and ((1 shl bitsLeft) - 1) else 0
                }
            }
        return output.toByteArray()
    }
}

private object IosUnsupportedKeyPairGenerator : KeyPairGenerator {
    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero = throw unsupportedCrypto()

    override fun ed25519(): KeyParameterRawZero = throw unsupportedCrypto()

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero = throw unsupportedCrypto()

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair = throw unsupportedCrypto()

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = null

    override fun getPrivateKeyLengthOrNull(
        privateKey: String,
    ): Int? = null
}

private object IosPermissionService : PermissionService {
    override fun getState(permission: Permission): Flow<PermissionState> =
        flowOf(PermissionState.Granted)
}

private object IosClearData : ClearData {
    @OptIn(ExperimentalForeignApi::class)
    override fun invoke() = ioEffect {
        NSFileManager.defaultManager.removeItemAtPath(
            path = iosKeyguardDataDirectory().value,
            error = null,
        )
        Unit
    }
}

private object IosGetPurchased : GetPurchased {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

private object IosSubscriptionService : SubscriptionService {
    override fun purchased(): Flow<RichResult<Boolean>> =
        flowOf(RichResult.Success(false))

    override fun subscriptions(): Flow<List<Subscription>?> =
        flowOf(emptyList())

    override fun products(): Flow<List<Product>?> =
        flowOf(emptyList())
}

private object IosGetPasswordStrength : GetPasswordStrength {
    override fun invoke(password: String) = io(
        PasswordStrength(
            crackTimeSeconds = when {
                password.length < 8 -> 1_000L
                password.length < 12 -> 100_000_000L
                else -> 1_000_000_000_000L
            },
            version = 0L,
        ),
    )
}

private object IosDateFormatter : DateFormatter {
    override fun formatDateTimeMachine(
        instant: Instant,
    ): String = instant.toString()

    override fun formatDateTime(
        instant: Instant,
    ): String {
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${formatDateMedium(dateTime.date)} ${formatTimeShort(dateTime.time)}"
    }

    override fun formatDate(
        instant: Instant,
    ): String = formatDateMedium(
        instant.toLocalDateTime(TimeZone.currentSystemDefault()).date,
    )

    override suspend fun formatDateShort(
        instant: Instant,
    ): String = formatDate(
        instant = instant,
    )

    override suspend fun formatDateShort(
        date: LocalDate,
    ): String = formatDateMedium(date)

    override fun formatDateMedium(
        date: LocalDate,
    ): String = "${date.year}-${date.monthNumber.twoDigits()}-${date.dayOfMonth.twoDigits()}"

    override fun formatTimeShort(
        time: LocalTime,
    ): String = "${time.hour.twoDigits()}:${time.minute.twoDigits()}"
}

private object IosBiometricStatusUseCase : BiometricStatusUseCase {
    override fun invoke(): Flow<BiometricStatus> = flowOf(BiometricStatus.Unavailable)
}

private object IosGetAppBuildDate : GetAppBuildDate {
    override fun invoke(): Flow<String> = flowOf("unknown")
}

private object IosGetAppBuildRef : GetAppBuildRef {
    override fun invoke(): Flow<String> = flowOf("unknown")
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun unsupportedCrypto() = UnsupportedOperationException(
    "Cipher encryption is not supported on iOS yet.",
)

private fun unsupportedIosFeature(
    feature: String,
) = UnsupportedOperationException("$feature is not supported on iOS yet.")
