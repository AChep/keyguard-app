package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.common.service.icon.IconResponseTooLargeException
import com.artemchep.keyguard.common.service.icon.ResolvedIcon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.provider.bitwarden.transport.AccountHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GetIconImageImplTest {
    private val request = FaviconUrl(url = "https://example.com")

    @Test
    fun `downloads the resolved icon`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val getIconImage = GetIconImageImpl(resolver(respondWith = bytes))

        assertContentEquals(bytes, getIconImage(request).bind())
    }

    @Test
    fun `emits nothing if the icon must not be downloaded`() = runTest {
        val getIconImage = GetIconImageImpl(
            object : IconResolver {
                override suspend fun isAllowed(request: IconRequest) = false
                override suspend fun resolveAllowed(request: IconRequest): ResolvedIcon? = null
            },
        )

        assertNull(getIconImage(request).bind())
    }

    @Test
    fun `fails on large icons`() = runTest {
        val bytes = ByteArray(GetIconImageImpl.MAX_ICON_BYTES.toInt() + 1)
        val getIconImage = GetIconImageImpl(resolver(respondWith = bytes))

        val result = getIconImage(request).attempt().bind()

        assertIs<IconResponseTooLargeException>(result.leftOrNull())
    }

    @Test
    fun `fails on unsuccessful responses`() = runTest {
        val getIconImage = GetIconImageImpl(resolver(status = HttpStatusCode.NotFound))

        val result = getIconImage(request).attempt().bind()

        assertIs<Exception>(result.leftOrNull())
    }

    private fun resolver(
        respondWith: ByteArray = byteArrayOf(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = object : IconResolver {
        private val httpClient = HttpClient(MockEngine { respond(respondWith, status) })

        override suspend fun isAllowed(request: IconRequest) = true

        override suspend fun resolveAllowed(request: IconRequest) = ResolvedIcon(
            url = "https://example.com/favicon.ico",
            client = AccountHttpClient(httpClient),
        )
    }
}
