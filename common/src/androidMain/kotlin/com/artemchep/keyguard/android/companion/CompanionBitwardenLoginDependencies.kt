package com.artemchep.keyguard.android.companion

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientFactory
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProvider
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProviderImpl
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsService
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsServiceNative
import com.artemchep.keyguard.provider.bitwarden.usecase.DiscoverBitwardenServer
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfaImpl
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Dependencies of the screen that signs a watch in to Bitwarden.
 *
 * The phone signs the watch in without unlocking its own vault, so
 * everything comes from the root scope, never from the vault session.
 * The screen owns an instance and closes it when it leaves.
 */
class CompanionBitwardenLoginDependencies(
    httpClient: HttpClient,
    httpClientFactory: BitwardenHttpClientFactory,
    val companionAuthBridge: CompanionAuthBridgeAndroid,
    val cryptoGenerator: CryptoGenerator,
    val base64Service: Base64Service,
    val deviceIdUseCase: DeviceIdUseCase,
    val json: Json,
    val cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    val dateFormatter: DateFormatter,
    val fileService: FileService,
    val confirmationRouteFactory: ConfirmationRouteFactory,
    val discoverBitwardenServer: DiscoverBitwardenServer,
) : AutoCloseable {
    val httpClientProvider: BitwardenHttpClientProvider = BitwardenHttpClientProviderImpl(
        httpClient = httpClient,
        factory = httpClientFactory,
    )

    val requestEmailTfa: RequestEmailTfa = RequestEmailTfaImpl(
        deviceIdUseCase = deviceIdUseCase,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        httpClientProvider = httpClientProvider,
    )

    val mutualTlsService: MutualTlsService = MutualTlsServiceNative()

    override fun close() {
        httpClientProvider.close()
    }
}
