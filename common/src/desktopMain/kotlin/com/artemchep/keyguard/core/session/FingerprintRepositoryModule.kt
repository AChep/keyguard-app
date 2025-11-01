package com.artemchep.keyguard.core.session

import arrow.core.partially1
import arrow.core.partially2
import arrow.optics.Getter
import com.artemchep.autotype.biometricsIsSupported
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.impl.GetLocaleImpl
import com.artemchep.keyguard.common.usecase.impl.PutLocaleImpl
import com.artemchep.keyguard.copy.ClipboardServiceJvm
import com.artemchep.keyguard.copy.ConnectivityServiceJvm
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.DownloadClientDesktop
import com.artemchep.keyguard.copy.DownloadManagerDesktop
import com.artemchep.keyguard.copy.DownloadRepositoryDesktop
import com.artemchep.keyguard.copy.DownloadTaskDesktop
import com.artemchep.keyguard.copy.FileServiceJvm
import com.artemchep.keyguard.copy.FileWatcherServiceJvm
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.PermissionServiceJvm
import com.artemchep.keyguard.copy.PowerServiceJvm
import com.artemchep.keyguard.copy.ReviewServiceJvm
import com.artemchep.keyguard.copy.TextServiceJvm
import com.artemchep.keyguard.core.session.BiometricStatusUseCaseImpl
import com.artemchep.keyguard.di.globalModuleJvm
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeBiometricCipherKeychain
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.util.traverse
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
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val keychainRepository: KeychainRepository,
) : BiometricStatusUseCase {
    constructor(directDI: DirectDI) : this(
        base64Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        keychainRepository = directDI.instance(),
    )

    override fun invoke(): Flow<BiometricStatus> = flow {
        // FIXME: Properly load native library on Flatpak platform
        //  instead of just assuming that only MacOS supports biometrics.
        val hasBiometrics = CurrentPlatform is Platform.Desktop.MacOS && hasBiometrics()
        val event = if (hasBiometrics) {
            BiometricStatus.Available(
                createCipher = { purpose ->
                    val cipher = LeBiometricCipherKeychain(
                        defer = ::populateCipherWithParams
                            .partially2(purpose),
                        forEncryption = purpose is BiometricPurpose.Encrypt,
                    )
                    cipher
                },
                deleteCipher = {
                    keychainRepository.delete(KeychainIds.BIOMETRIC_UNLOCK.value)
                        .bind()
                },
            )
        } else {
            BiometricStatus.Unavailable
        }
        emit(event)
    }

    private suspend fun hasBiometrics(): Boolean {
        return biometricsIsSupported()
    }

    private fun populateCipherWithParams(
        cipher: LeBiometricCipherKeychain,
        purpose: BiometricPurpose,
    ) {
        when (purpose) {
            is BiometricPurpose.Encrypt -> {
                // Init cipher in encrypt mode with random iv
                // seed. The user should persist iv for future use.
                cipher._iv = cryptoGenerator.seed(length = 16)

                val key = cryptoGenerator.seed(length = 32)
                val keyBase64 = base64Service.encodeToString(key)
                // Save the key in the login keychain.
                keychainRepository.put(KeychainIds.BIOMETRIC_UNLOCK.value, keyBase64)
                    .bindBlocking()
                cipher._key = key
            }

            is BiometricPurpose.Decrypt -> {
                cipher._iv = purpose.iv.byteArray
                // Obtain the cipher key from the
                // login keychain.
                val keyBase64 = keychainRepository.get(KeychainIds.BIOMETRIC_UNLOCK.value)
                    .bindBlocking()
                cipher._key = base64Service.decode(keyBase64)
            }
        }
    }
}

class GetSuggestionsImpl : GetSuggestions<Any?> {
    override fun invoke(
        p1: List<Any?>,
        p2: Getter<Any?, DSecret>,
        p3: AutofillTarget,
        p4: EquivalentDomainsBuilderFactory,
    ): IO<List<Any?>> =
        ioRaise(RuntimeException())
}

class GetPurchasedImpl : GetPurchased {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

class CleanUpAttachmentImpl : CleanUpAttachment {
    override fun invoke(): IO<Int> =
        ioRaise(RuntimeException())
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

    override suspend fun get(): File {
        val path = dataDirectory.cache().bind()
        return File(path)
    }

    override fun getBlocking(): File {
        val path = dataDirectory.cacheBlocking()
        return File(path)
    }
}

fun diFingerprintRepositoryModule() = DI.Module(
    name = "com.artemchep.keyguard.core.session.repository::FingerprintRepository",
) {
    import(globalModuleJvm())

    bindProvider<LeContext>() {
        LeContext()
    }
    bindSingleton<BiometricStatusUseCase> {
        BiometricStatusUseCaseImpl(
            directDI = this,
        )
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
    bindSingleton<GetSuggestions<Any?>> {
        GetSuggestionsImpl()
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
        TextServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<FileService> {
        FileServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<ReviewService> {
        ReviewServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<DownloadClientDesktop> {
        DownloadClientDesktop(
            directDI = this,
        )
    }
    bindSingleton<DownloadTask> {
        DownloadTaskDesktop(
            directDI = this,
        )
    }
    bindSingleton<DownloadManager> {
        DownloadManagerDesktop(
            directDI = this,
        )
    }
    bindSingleton<DownloadRepository> {
        DownloadRepositoryDesktop(this)
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
            fileIo = d.data().map { File(it, key.filename) },
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
    bindSingleton<LogRepositoryKotlin> {
        LogRepositoryKotlin()
    }
}

