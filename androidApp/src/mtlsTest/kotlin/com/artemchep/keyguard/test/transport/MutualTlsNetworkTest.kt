package com.artemchep.keyguard.test.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.provider.bitwarden.transport.mutualTlsJvmTestClients
import com.artemchep.keyguard.provider.bitwarden.transport.verifyMutualTlsTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MutualTlsNetworkTest {
    @Test
    fun handshakesRedirectsAndWebSocketsUseOnlyConfiguredIdentities() = runBlocking {
        HttpClient(OkHttp).use { bootstrap ->
            verifyMutualTlsTransport(
                InstrumentationRegistry.getArguments().getString("mtlsPort"),
                bootstrap,
                ::mutualTlsJvmTestClients,
            )
        }
    }
}
