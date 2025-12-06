package com.artemchep.keyguard.core.session

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.artemchep.keyguard.android.downloader.DownloadClientAndroid
import com.artemchep.keyguard.android.downloader.DownloadManagerImpl
import com.artemchep.keyguard.android.downloader.DownloadTaskAndroid
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.DownloadRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.room.DownloadDatabaseManager
import com.artemchep.keyguard.android.notiifcation.NotificationRepositoryAndroid
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManagerImpl
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.keychain.impl.KeychainRepositoryNoOp
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.CleanUpAttachment
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.GetPurchasedImpl
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.copy.AutofillServiceAndroid
import com.artemchep.keyguard.copy.ClearDataAndroid
import com.artemchep.keyguard.copy.ClipboardServiceAndroid
import com.artemchep.keyguard.copy.ConnectivityServiceAndroid
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.copy.LinkInfoExtractorAndroid
import com.artemchep.keyguard.copy.DirsServiceAndroid
import com.artemchep.keyguard.copy.FileServiceAndroid
import com.artemchep.keyguard.copy.FileWatcherServiceAndroid
import com.artemchep.keyguard.copy.LinkInfoExtractorLaunch
import com.artemchep.keyguard.copy.LogRepositoryAndroid
import com.artemchep.keyguard.copy.PermissionServiceAndroid
import com.artemchep.keyguard.copy.PowerServiceAndroid
import com.artemchep.keyguard.copy.ReviewServiceAndroid
import com.artemchep.keyguard.copy.SharedPreferencesArg
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactory
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryV1
import com.artemchep.keyguard.copy.SharedPreferencesStoreFactoryV2
import com.artemchep.keyguard.copy.SharedPreferencesTypes
import com.artemchep.keyguard.copy.SubscriptionServiceAndroid
import com.artemchep.keyguard.copy.TextServiceAndroid
import com.artemchep.keyguard.core.session.usecase.BiometricStatusUseCaseImpl
import com.artemchep.keyguard.core.session.usecase.DatabaseSqlManagerInFileAndroid
import com.artemchep.keyguard.core.session.usecase.GetLocaleAndroid
import com.artemchep.keyguard.core.session.usecase.PutLocaleAndroid
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.di.globalModuleJvm
import com.artemchep.keyguard.platform.LeContext
import db_key_value.datastore.encrypted.SecureDataStoreKeyValueStore
import db_key_value.shared_prefs.encrypted.SecureSharedPrefsKeyValueStore
import db_key_value.datastore.DataStoreKeyValueStore
import db_key_value.shared_prefs.SharedPrefsKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.factory
import org.kodein.di.instance
import org.kodein.di.multiton
import java.io.File

class CacheDirProviderAndroid(
    private val context: Context,
) : CacheDirProvider {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
    )

    override suspend fun get(): File = withContext(Dispatchers.IO) {
        getBlocking()
    }

    override fun getBlocking(): File = context.cacheDir
}

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
    bindSingleton<KeychainRepository> {
        KeychainRepositoryNoOp(
            directDI = this,
        )
    }
    bindSingleton<NotificationRepository> {
        NotificationRepositoryAndroid(
            directDI = this,
        )
    }
    bindSingleton<GetBarcodeImage> {
        GetBarcodeImageJvm(
            directDI = this,
        )
    }


    bindSingleton<CacheDirProvider> {
        CacheDirProviderAndroid(
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
    bindSingleton<DirsService> {
        DirsServiceAndroid(this)
    }
    bindSingleton<FileWatcherService> {
        FileWatcherServiceAndroid(this)
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
    bindSingleton<TextService> {
        TextServiceAndroid(
            directDI = this,
        )
    }
    bindSingleton<FileService> {
        FileServiceAndroid(
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
    bindSingleton<DownloadTask> {
        DownloadTaskAndroid(
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
    bind<KeyValueStore>(SharedPreferencesTypes.SHARED_PREFS_ENCRYPTED) with multiton { arg: SharedPreferencesArg ->
        SecureSharedPrefsKeyValueStore(
            context = instance<Application>(),
            file = arg.key.filename,
            logRepository = instance(),
        )
    }
    bind<KeyValueStore>(SharedPreferencesTypes.SHARED_PREFS) with multiton { arg: SharedPreferencesArg ->
        SharedPrefsKeyValueStore(
            context = instance<Application>(),
            file = arg.key.filename,
            logRepository = instance(),
        )
    }
    bind<KeyValueStore>(SharedPreferencesTypes.DATA_STORE_ENCRYPTED) with multiton { arg: SharedPreferencesArg ->
        SecureDataStoreKeyValueStore(
            context = instance<Application>(),
            file = arg.key.filename,
            logRepository = instance(),
            backingStore = arg.store,
        )
    }
    bind<KeyValueStore>(SharedPreferencesTypes.DATA_STORE) with multiton { arg: SharedPreferencesArg ->
        DataStoreKeyValueStore(
            context = instance<Application>(),
            file = arg.key.filename,
            logRepository = instance(),
            backingStore = arg.store,
        )
    }
    bindSingleton {
        SharedPreferencesStoreFactoryV1()
    }
    bindSingleton {
        SharedPreferencesStoreFactoryV2(this)
    }
    bindProvider<SharedPreferencesStoreFactory> {
        instance<SharedPreferencesStoreFactoryV2>()
    }
    bindSingleton<DownloadDatabaseManager> {
        DownloadDatabaseManager(
            applicationContext = instance<Application>(),
            name = "download",
            deviceEncryptionKeyUseCase = instance(),
        )
    }
    bindSingleton<ExposedDatabaseManager> {
        val sqlManager = DatabaseSqlManagerInFileAndroid<DatabaseExposed>(
            context = instance<Application>(),
            fileName = "database_exposed",
            onCreate = { database ->
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
    bindSingleton<LogRepositoryAndroid> {
        LogRepositoryAndroid()
    }
}
