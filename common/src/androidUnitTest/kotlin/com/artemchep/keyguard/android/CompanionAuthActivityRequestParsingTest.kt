package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanionAuthActivityRequestParsingTest {
    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `parses bitwarden request`() {
        val request = parseCompanionAuthRequest(
            requestId = requestId,
            rawProvider = CompanionAuthProvider.BITWARDEN.name,
        )

        assertEquals(
            CompanionAuthActivity.Request(
                requestId = requestId,
                provider = CompanionAuthProvider.BITWARDEN,
            ),
            request,
        )
    }

    @Test
    fun `parses keepass request`() {
        val request = parseCompanionAuthRequest(
            requestId = requestId,
            rawProvider = CompanionAuthProvider.KEEPASS.name,
        )

        assertEquals(
            CompanionAuthActivity.Request(
                requestId = requestId,
                provider = CompanionAuthProvider.KEEPASS,
            ),
            request,
        )
    }

    @Test
    fun `malformed provider returns null`() {
        val request = parseCompanionAuthRequest(
            requestId = requestId,
            rawProvider = "NOT_A_PROVIDER",
        )

        assertNull(request)
    }

    @Test
    fun `missing provider returns null`() {
        val request = parseCompanionAuthRequest(
            requestId = requestId,
            rawProvider = null,
        )

        assertNull(request)
    }

    @Test
    fun `missing request id returns null`() {
        val request = parseCompanionAuthRequest(
            requestId = null,
            rawProvider = CompanionAuthProvider.BITWARDEN.name,
        )

        assertNull(request)
    }

    @Test
    fun `mixed case provider returns null`() {
        val request = parseCompanionAuthRequest(
            requestId = requestId,
            rawProvider = "bitwarden",
        )

        assertNull(request)
    }

    @Test
    fun `non canonical request id returns null`() {
        val request = parseCompanionAuthRequest(
            requestId = "123E4567-E89B-12D3-A456-426614174000",
            rawProvider = CompanionAuthProvider.BITWARDEN.name,
        )

        assertNull(request)
    }
}
