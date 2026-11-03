package com.artemchep.keyguard.provider.bitwarden.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class MutualTlsNetworkTest {
    @Test
    fun handshakesRedirectsAndWebSocketsUseOnlyConfiguredIdentities() = runBlocking {
        HttpClient(OkHttp).use { bootstrap ->
            verifyMutualTlsTransport(System.getenv("KEYGUARD_MTLS_PORT"), bootstrap, ::mutualTlsJvmTestClients)
        }
    }
}
