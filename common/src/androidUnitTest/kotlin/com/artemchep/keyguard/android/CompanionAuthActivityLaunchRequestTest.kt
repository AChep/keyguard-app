package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CompanionAuthActivityLaunchRequestTest {
    @Test
    fun `invalid request does not call launch resolver`() = runTest {
        var resolverCalls = 0

        val request = resolveCompanionAuthLaunchRequest(
            currentRequest = null,
            requestId = REQUEST_ID_A,
            rawProvider = "NOT_A_PROVIDER",
        ) { _, _ ->
            resolverCalls++
            request(id = REQUEST_ID_A)
        }

        assertNull(request)
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `fresh valid request calls launch resolver`() = runTest {
        val expectedRequest = request(id = REQUEST_ID_A)
        val resolverCalls = mutableListOf<Pair<String, CompanionAuthProvider>>()

        val request = resolveCompanionAuthLaunchRequest(
            currentRequest = null,
            requestId = REQUEST_ID_A,
            rawProvider = CompanionAuthProvider.BITWARDEN.name,
        ) { requestId, provider ->
            resolverCalls += requestId to provider
            expectedRequest
        }

        assertEquals(expectedRequest, request)
        assertEquals(
            listOf(REQUEST_ID_A to CompanionAuthProvider.BITWARDEN),
            resolverCalls,
        )
    }

    @Test
    fun `duplicate active request does not call launch resolver`() = runTest {
        val currentRequest = request(
            id = REQUEST_ID_A,
            provider = CompanionAuthProvider.KEEPASS,
        )
        var resolverCalled = false

        val request = resolveCompanionAuthLaunchRequest(
            currentRequest = currentRequest,
            requestId = REQUEST_ID_A,
            rawProvider = CompanionAuthProvider.KEEPASS.name,
        ) { _, _ ->
            resolverCalled = true
            null
        }

        assertEquals(currentRequest, request)
        assertFalse(resolverCalled)
    }

    @Test
    fun `different valid request calls launch resolver and replaces current request`() = runTest {
        val currentRequest = request(id = REQUEST_ID_A)
        val expectedRequest = request(
            id = REQUEST_ID_B,
            provider = CompanionAuthProvider.KEEPASS,
        )
        val resolverCalls = mutableListOf<Pair<String, CompanionAuthProvider>>()

        val incomingRequest = resolveCompanionAuthLaunchRequest(
            currentRequest = currentRequest,
            requestId = REQUEST_ID_B,
            rawProvider = CompanionAuthProvider.KEEPASS.name,
        ) { requestId, provider ->
            resolverCalls += requestId to provider
            expectedRequest
        }
        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = currentRequest,
            completedRequestId = null,
            incomingRequest = incomingRequest,
        )

        assertEquals(expectedRequest, incomingRequest)
        assertEquals(
            listOf(REQUEST_ID_B to CompanionAuthProvider.KEEPASS),
            resolverCalls,
        )
        assertEquals(expectedRequest, update.request)
        assertEquals(currentRequest, update.requestToCancel)
        assertFalse(update.shouldFinish)
    }

    private companion object {
        const val REQUEST_ID_A = "123e4567-e89b-12d3-a456-426614174000"
        const val REQUEST_ID_B = "223e4567-e89b-12d3-a456-426614174000"

        fun request(
            id: String,
            provider: CompanionAuthProvider = CompanionAuthProvider.BITWARDEN,
        ) = CompanionAuthActivity.Request(
            requestId = id,
            provider = provider,
        )
    }
}
