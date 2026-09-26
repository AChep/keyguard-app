package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifier
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandJvm
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.licensekey.EcdsaP256LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.RunBackupNow
import com.artemchep.keyguard.common.usecase.TestBackupLocation
import com.artemchep.keyguard.common.usecase.impl.CheckWebDavConnectionImpl
import com.artemchep.keyguard.common.usecase.impl.ListWebDavDirectoryImpl
import com.artemchep.keyguard.common.usecase.impl.RunBackupNowImpl
import com.artemchep.keyguard.common.usecase.impl.TestBackupLocationImpl
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.copy.DateFormatterJvm
import com.artemchep.keyguard.copy.GetAppBuildDateImpl
import com.artemchep.keyguard.copy.GetAppBuildRefImpl
import com.artemchep.keyguard.copy.NumberFormatterJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.FileEncryptionCodecJvm
import com.artemchep.keyguard.crypto.NativeGpgKeyExpirationService
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpService
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpVerifier
import com.artemchep.keyguard.crypto.ssl.installPlatformTrustManager
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadServiceJvm
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.CurlUserAgent
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

class GlobalModuleJvm {
    val module = module {

        factory<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>()) {
            Dispatchers.IO
        }
        single<CoroutineScope>(qualifier = named<ApplicationCoroutineScope>()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        } onClose { scope ->
            scope?.cancel()
        }
        single<SshAgentApprovalWindowMemory> {
            SshAgentApprovalWindowMemory(
                getSshAgentApprovalWindow = get<GetSshAgentApprovalWindow>(),
                getVaultSession = get<GetVaultSession>(),
                scope = get<CoroutineScope>(qualifier = named<ApplicationCoroutineScope>()),
                getSshAgentApprovalCachePolicy = get<GetSshAgentApprovalCachePolicy>(),
            )
        }
        single<LicenseSignatureVerifier> {
            EcdsaP256LicenseSignatureVerifier()
        }
        single<Base64Service> {
            Base64ServiceJvm()
        }
        single<Base32Service> {
            Base32ServiceJvm()
        }
        single<GetAppBuildDate> {
            GetAppBuildDateImpl(
                formatter = get(),
            )
        }
        single<GetAppBuildRef> {
            GetAppBuildRefImpl()
        }
        // Repositories
        single<Json> {
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                prettyPrint = false
                isLenient = true
                serializersModule = SerializersModule {
                    // default
                    polymorphic(BitwardenCipher.Attachment::class) {
                        subclass(BitwardenCipher.Attachment.Remote::class)
                        subclass(BitwardenCipher.Attachment.Local::class)
                        defaultDeserializer { BitwardenCipher.Attachment.Remote.serializer() }
                    }
                    // database
                    polymorphic(ServiceToken::class) {
                        subclass(BitwardenToken::class)
                        subclass(KeePassToken::class)
                        defaultDeserializer { BitwardenToken.serializer() }
                    }
                    // privileged apps
                    polymorphic(PrivilegedAppListEntity.App::class) {
                        subclass(PrivilegedAppListEntity.App.AndroidApp::class)
                        subclass(PrivilegedAppListEntity.App.Unknown::class)
                        defaultDeserializer { PrivilegedAppListEntity.App.Unknown.serializer() }
                    }
                }
            }
        }
        single<FileEncryptionCodec> {
            FileEncryptionCodecJvm(cryptoGenerator = get(), stagingSpoolFactory = get())
        }
        single<EncryptedFilePendingUploadService> {
            EncryptedFilePendingUploadServiceJvm(
                dirProvider = get(),
                fileService = get(),
                fileEncryptionCodec = get(),
                stagingSpoolFactory = get(),
            )
        }
        single<ExecuteCommand> {
            ExecuteCommandJvm()
        }
        single<RunBackupNow> {
            RunBackupNowImpl(
                backupRunService = get(),
            )
        }
        single<CheckWebDavConnection> {
            CheckWebDavConnectionImpl(
                httpClient = get(),
            )
        }
        single<ListWebDavDirectory> {
            ListWebDavDirectoryImpl(
                httpClient = get(),
            )
        }
        single<TestBackupLocation> {
            TestBackupLocationImpl(
                backupObjectStoreFactory = get(),
            )
        }
        single<DateFormatter> {
            DateFormatterJvm(
                context = get(),
            )
        }
        single<NumberFormatter> {
            NumberFormatterJvm()
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
        single<GpgOpenPgpService> {
            NativeGpgOpenPgpService(
                stagingSpoolFactory = get(),
            )
        }
        single<GpgOpenPgpVerifier> {
            NativeGpgOpenPgpVerifier
        }
        single<HttpClient> {
            val json: Json = get()
            val okHttpClient: OkHttpClient = get()
            HttpClient(OkHttp) {
                install(UserAgent) {
                    agent = BitwardenPersona.of(CurrentPlatform)
                        .userAgent
                }
                engine {
                    preconfigured = okHttpClient
                }
    //            install(Logging) {
    //                level = if (isRelease) LogLevel.INFO else LogLevel.ALL
    //            }
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
                    configureBitwardenHttpRetry()
                }
            }
        }
        single<HttpClient>(qualifier = named("curl")) {
            val json: Json = get()
            val okHttpClient: OkHttpClient = get()
            HttpClient(OkHttp) {
                CurlUserAgent()
                engine {
                    preconfigured = okHttpClient
                }
    //            install(Logging) {
    //                level = if (isRelease) LogLevel.INFO else LogLevel.ALL
    //            }
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
                    configureBitwardenHttpRetry()
                }
            }
        }
        single<OkHttpClient> {
            val timeouts = with(Duration) { 30.seconds }
            OkHttpClient
                .Builder()
                .installPlatformTrustManager()
                .connectTimeout(timeouts)
                .readTimeout(timeouts)
                .writeTimeout(timeouts)
                .apply {
                    if (!isRelease) {
                        val logRepository: LogRepository = get()
                        val logger = HttpLoggingInterceptor.Logger { message ->
    //                        logRepository.post(
    //                            qualifier = named("OkHttp"),
    //                            message = message,
    //                        )
                        }
                        val logging = HttpLoggingInterceptor(logger).apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        }
                        // addInterceptor(logging)
                    }
                }
                .build()
        }
    }
}
