package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchGpgPublicKeyImplTest {
    @Test
    fun `servable query is sent to the configured keyserver`() = runTest {
        val config = GpgKeyserverConfig()
        val client = FakeKeyserverClient(
            canServe = true,
            results = listOf(result),
        )
        val useCase = SearchGpgPublicKeyImpl(
            getGpgKeyserverConfig = getConfig(config),
            keyserverClient = client,
        )

        val results = useCase(SearchGpgPublicKeyRequest("alice@example.com")).bind()

        assertEquals(listOf(result), results)
        // The configured server is queried directly; no fallback re-route.
        assertEquals(
            listOf(SearchGpgPublicKeyRequest("alice@example.com") to config),
            client.searchCalls,
        )
    }

    @Test
    fun `unservable free-text query falls back to Ubuntu HKP index`() = runTest {
        val config = GpgKeyserverConfig()
        val client = FakeKeyserverClient(
            canServe = false,
            results = listOf(result),
        )
        val useCase = SearchGpgPublicKeyImpl(
            getGpgKeyserverConfig = getConfig(config),
            keyserverClient = client,
        )

        val request = SearchGpgPublicKeyRequest(
            query = "Alice Example",
            keyserver = "https://custom.example",
        )
        val results = useCase(request).bind()

        assertEquals(listOf(result), results)
        // The fallback targets the Ubuntu HKP server and drops the per-request
        // keyserver override, exactly as the previous in-client fallback did.
        assertEquals(
            listOf(
                SearchGpgPublicKeyRequest(
                    query = "Alice Example",
                    keyserver = null,
                ) to GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            ),
            client.searchCalls,
        )
    }

    @Test
    fun `servable request keyserver config is not rerouted through fallback`() = runTest {
        val config = GpgKeyserverConfig()
        val hkpConfig = GpgKeyserverConfig(
            url = GpgKeyserverConfig.HKP_UBUNTU_URL,
            protocol = GpgKeyserverConfig.Protocol.HKP,
        )
        val client = FakeKeyserverClient(
            canServe = { _, canServeConfig ->
                canServeConfig.protocol == GpgKeyserverConfig.Protocol.HKP
            },
            results = listOf(result),
        )
        val useCase = SearchGpgPublicKeyImpl(
            getGpgKeyserverConfig = getConfig(config),
            keyserverClient = client,
        )

        val request = SearchGpgPublicKeyRequest(
            query = "Alice Example",
            keyserverConfig = hkpConfig,
        )
        val results = useCase(request).bind()

        assertEquals(listOf(result), results)
        assertEquals(listOf(request to hkpConfig), client.canServeCalls)
        assertEquals(listOf(request to config), client.searchCalls)
    }

    private fun getConfig(
        config: GpgKeyserverConfig,
    ) = object : GetGpgKeyserverConfig {
        override fun invoke(): Flow<GpgKeyserverConfig> = flowOf(config)
    }

    private class FakeKeyserverClient(
        private val canServe: (SearchGpgPublicKeyRequest, GpgKeyserverConfig) -> Boolean,
        private val results: List<DGpgKeyserverResult>,
    ) : GpgKeyserverClient {
        constructor(
            canServe: Boolean,
            results: List<DGpgKeyserverResult>,
        ) : this(
            canServe = { _, _ -> canServe },
            results = results,
        )

        val canServeCalls = mutableListOf<Pair<SearchGpgPublicKeyRequest, GpgKeyserverConfig>>()
        val searchCalls = mutableListOf<Pair<SearchGpgPublicKeyRequest, GpgKeyserverConfig>>()

        override fun search(
            request: SearchGpgPublicKeyRequest,
            config: GpgKeyserverConfig,
        ): IO<List<DGpgKeyserverResult>> = ioEffect {
            searchCalls += request to config
            results
        }

        override fun canServeSearch(
            request: SearchGpgPublicKeyRequest,
            config: GpgKeyserverConfig,
        ): Boolean {
            canServeCalls += request to config
            return canServe(request, config)
        }

        override fun getByFingerprint(
            fingerprint: String,
            config: GpgKeyserverConfig,
        ): IO<DGpgKeyserverResult?> = ioEffect {
            error("Not used by search.")
        }

        override fun getByEmail(
            email: String,
            config: GpgKeyserverConfig,
        ): IO<List<DGpgKeyserverResult>> = ioEffect {
            error("Not used by search.")
        }

        override fun upload(
            publicKeyArmored: String,
            config: GpgKeyserverConfig,
        ): IO<DGpgKeyserverUploadResult> = ioEffect {
            error("Not used by search.")
        }

        override fun requestVerify(
            token: String,
            addresses: Collection<String>,
            config: GpgKeyserverConfig,
        ): IO<DGpgKeyserverUploadResult> = ioEffect {
            error("Not used by search.")
        }
    }

    companion object {
        private val result = DGpgKeyserverResult(
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            emails = listOf("alice@example.com"),
            sourceKeyserver = GpgKeyserverConfig.DEFAULT_URL,
        )
    }
}
