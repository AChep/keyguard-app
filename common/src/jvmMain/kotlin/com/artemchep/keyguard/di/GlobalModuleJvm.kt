package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.common.service.execute.impl.ExecuteCommandJvm
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.zip.ZipService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.copy.DateFormatterJvm
import com.artemchep.keyguard.copy.GetAppBuildDateImpl
import com.artemchep.keyguard.copy.GetAppBuildRefImpl
import com.artemchep.keyguard.copy.GetPasswordStrengthJvm
import com.artemchep.keyguard.copy.NumberFormatterJvm
import com.artemchep.keyguard.copy.ZipServiceJvm
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.FileEncryptorJvm
import com.artemchep.keyguard.crypto.KeyPairGeneratorJvm
import com.artemchep.keyguard.crypto.SshKeyImportServiceJvm
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun globalModuleJvm() = DI.Module(
    name = "globalModuleJvm",
) {
    import(globalModuleCommon())

    bindProvider<CoroutineDispatcher>(tag = DatabaseDispatcher) {
        Dispatchers.IO
    }
    bindSingleton<Base64Service> {
        Base64ServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<Base32Service> {
        Base32ServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<GetAppBuildDate> {
        GetAppBuildDateImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppBuildRef> {
        GetAppBuildRefImpl(
            directDI = this,
        )
    }
    // Repositories
    bindSingleton<Json> {
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
    bindSingleton<CipherEncryptor> {
        CipherEncryptorImpl(
            directDI = this,
        )
    }
    bindSingleton<FileEncryptor> {
        FileEncryptorJvm(
            directDI = this,
        )
    }
    bindSingleton<EncryptedFilePendingUploadService> {
        EncryptedFilePendingUploadServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<ExecuteCommand> {
        ExecuteCommandJvm(
            directDI = this,
        )
    }
    bindSingleton<ZipService> {
        ZipServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<GetPasswordStrength> {
        GetPasswordStrengthJvm(
            directDI = this,
        )
    }
    bindSingleton<DateFormatter> {
        DateFormatterJvm(
            directDI = this,
        )
    }
    bindSingleton<NumberFormatter> {
        NumberFormatterJvm(
            directDI = this,
        )
    }
    bindSingleton<CryptoGenerator> {
        CryptoGeneratorJvm()
    }
    bindSingleton<KeyPairGenerator> {
        KeyPairGeneratorJvm(
            directDI = this,
        )
    }
    bindSingleton<SshKeyImportService> {
        SshKeyImportServiceJvm(
            directDI = this,
        )
    }
    bindSingleton<HttpClient> {
        val json: Json = instance()
        val okHttpClient: OkHttpClient = instance()
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
    bindSingleton<HttpClient>(tag = "curl") {
        val json: Json = instance()
        val okHttpClient: OkHttpClient = instance()
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
    bindSingleton<OkHttpClient> {
        OkHttpClient
            .Builder()
            .installPlatformTrustManager()
            .apply {
                if (!isRelease) {
                    val logRepository: LogRepository = instance()
                    val logger = HttpLoggingInterceptor.Logger { message ->
//                        logRepository.post(
//                            tag = "OkHttp",
//                            message = message,
//                        )
                    }
                    val logging = HttpLoggingInterceptor(logger).apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    }
                    addInterceptor(logging)
                }
            }
            .build()
    }
}
