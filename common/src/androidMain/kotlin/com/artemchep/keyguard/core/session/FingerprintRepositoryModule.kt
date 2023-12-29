package com.artemchep.keyguard.core.session

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.artemchep.keyguard.android.downloader.DownloadClientAndroid
import com.artemchep.keyguard.android.downloader.DownloadManagerImpl
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.DownloadRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.room.DownloadDatabaseManager
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
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
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.DisableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.EnableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRemainingDurationImpl
import com.artemchep.keyguard.common.usecase.impl.GetPurchasedImpl
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.copy.AutofillServiceAndroid
import com.artemchep.keyguard.copy.ClearDataAndroid
import com.artemchep.keyguard.copy.ClipboardServiceAndroid
import com.artemchep.keyguard.copy.ConnectivityServiceAndroid
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.LinkInfoExtractorAndroid
import com.artemchep.keyguard.copy.LinkInfoExtractorExecute
import com.artemchep.keyguard.copy.LinkInfoExtractorLaunch
import com.artemchep.keyguard.copy.LogRepositoryAndroid
import com.artemchep.keyguard.copy.PermissionServiceAndroid
import com.artemchep.keyguard.copy.PowerServiceAndroid
import com.artemchep.keyguard.copy.ReviewServiceAndroid
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactory
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryDefault
import com.artemchep.keyguard.copy.SubscriptionServiceAndroid
import com.artemchep.keyguard.copy.TextServiceAndroid
import com.artemchep.keyguard.core.session.usecase.BiometricStatusUseCaseImpl
import com.artemchep.keyguard.core.session.usecase.GetLocaleAndroid
import com.artemchep.keyguard.core.session.usecase.PutLocaleAndroid
import com.artemchep.keyguard.di.globalModuleJvm
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.util.isRelease
import db_key_value.crypto_prefs.SecurePrefKeyValueStore
import db_key_value.shared_prefs.SharedPrefsKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.factory
import org.kodein.di.instance
import org.kodein.di.multiton

fun diFingerprintRepositoryModule() = DI.Module(
    name = "com.artemchep.keyguard.core.session.repository::FingerprintRepository",
) {
    import(globalModuleJvm())

    bindProvider<LeContext>() {
        val context: Context = instance()
        LeContext(context)
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

    bindSingleton<GetBiometricRemainingDuration> {
        GetBiometricRemainingDurationImpl(
            directDI = this,
        )
    }
    bindSingleton<DisableBiometric> {
        DisableBiometricImpl(
            directDI = this,
        )
    }
    bindSingleton<EnableBiometric> {
        EnableBiometricImpl(
            directDI = this,
        )
    }


    bindSingleton<GetLocale> {
        GetLocaleAndroid(
            directDI = this,
        )
    }
    bindSingleton<PutLocale> {
        PutLocaleAndroid(
            directDI = this,
        )
    }
    bindSingleton<GetSuggestions<Any?>> {
        GetSuggestionsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPurchased> {
        GetPurchasedImpl(
            directDI = this,
        )
    }


    bindSingleton<CleanUpAttachment> {
        CleanUpAttachmentImpl(
            directDI = this,
        )
    }
//    bindSingleton<CleanUpDownloadImpl> {
//        CleanUpDownloadImpl(
//            directDI = this,
//        )
//    }
    bindSingleton<ClipboardService> {
        ClipboardServiceAndroid(this)
    }
    bindSingleton<ConnectivityService> {
        ConnectivityServiceAndroid(this)
    }
    bindSingleton<PowerService> {
        PowerServiceAndroid(this)
    }
    bindSingleton {
        PermissionServiceAndroid(this)
    }
    bindProvider<PermissionService> {
        instance<PermissionServiceAndroid>()
    }
    bindSingleton<LinkInfoExtractorAndroid> {
        LinkInfoExtractorAndroid(
            packageManager = instance(),
        )
    }
    bindSingleton<LinkInfoExtractorLaunch> {
        LinkInfoExtractorLaunch(
            packageManager = instance(),
        )
    }
    bindSingleton<LinkInfoExtractorExecute> {
        LinkInfoExtractorExecute()
    }
    bindSingleton<TextService> {
        TextServiceAndroid(
            directDI = this,
        )
    }
    bindSingleton<ReviewService> {
        ReviewServiceAndroid(
            directDI = this,
        )
    }
    bindProvider<PackageManager> {
        instance<Application>().packageManager
    }
    bindSingleton<DownloadClientAndroid> {
        DownloadClientAndroid(
            directDI = this,
        )
    }
    bindSingleton<DownloadManager> {
        DownloadManagerImpl(
            directDI = this,
        )
    }
    bindSingleton<DownloadRepository> {
        DownloadRepositoryImpl(
            directDI = this,
        )
    }
    bindSingleton<AutofillService> {
        AutofillServiceAndroid(
            directDI = this,
        )
    }
    bindSingleton<SubscriptionService> {
        SubscriptionServiceAndroid(
            directDI = this,
        )
    }
    bindSingleton<ClearData> {
        ClearDataAndroid(
            directDI = this,
        )
    }
    bind<KeyValueStore>() with factory { key: Files ->
        val factory = instance<SharedPreferencesStoreFactory>()
        factory.getStore(di, key)
    }
    bind<KeyValueStore>("proto") with multiton { file: Files ->
        SecurePrefKeyValueStore(
            context = instance<Application>(),
            file = file.filename,
        )
    }
    bind<KeyValueStore>("shared") with multiton { file: Files ->
        SharedPrefsKeyValueStore(
            context = instance<Application>(),
            file = file.filename,
        )
    }
    bindSingleton<SharedPreferencesStoreFactory> {
        SharedPreferencesStoreFactoryDefault()
    }
    bindSingleton<DownloadDatabaseManager> {
        DownloadDatabaseManager(
            applicationContext = instance<Application>(),
            name = "download",
            deviceEncryptionKeyUseCase = instance(),
        )
    }
    bindSingleton<LogRepository> {
        LogRepositoryAndroid()
    }
}
