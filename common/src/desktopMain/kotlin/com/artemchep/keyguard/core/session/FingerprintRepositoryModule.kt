package com.artemchep.keyguard.core.session

import arrow.core.partially1
import arrow.optics.Getter
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.WithBiometric
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
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
import com.artemchep.keyguard.copy.ExportManagerImpl
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.PermissionServiceJvm
import com.artemchep.keyguard.copy.PowerServiceJvm
import com.artemchep.keyguard.copy.ReviewServiceJvm
import com.artemchep.keyguard.copy.TextServiceJvm
import com.artemchep.keyguard.copy.download.DownloadTaskJvm
import com.artemchep.keyguard.di.globalModuleJvm
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.util.traverse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import kotlin.time.Duration

class BiometricStatusUseCaseImpl : BiometricStatusUseCase {
    override fun invoke(): Flow<BiometricStatus> = flowOf(BiometricStatus.Unavailable)
}

class GetBiometricRemainingDurationImpl : GetBiometricRemainingDuration {
    override fun invoke(): Flow<Duration> = flowOf(Duration.INFINITE)
}

class DisableBiometricImpl : DisableBiometric {
    override fun invoke(): IO<Unit> =
        ioRaise(RuntimeException())
}

class EnableBiometricImpl : EnableBiometric {
    override fun invoke(key: MasterSession.Key?): IO<WithBiometric> =
        ioRaise(RuntimeException())
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

fun diFingerprintRepositoryModule() = DI.Module(
    name = "com.artemchep.keyguard.core.session.repository::FingerprintRepository",
) {
    import(globalModuleJvm())

    bindProvider<LeContext>() {
        LeContext()
    }
    bindSingleton<BiometricStatusUseCase> {
        BiometricStatusUseCaseImpl()
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

    bindSingleton<GetBiometricRemainingDuration> {
        GetBiometricRemainingDurationImpl()
    }
    bindSingleton<DisableBiometric> {
        DisableBiometricImpl()
    }
    bindSingleton<EnableBiometric> {
        EnableBiometricImpl()
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

