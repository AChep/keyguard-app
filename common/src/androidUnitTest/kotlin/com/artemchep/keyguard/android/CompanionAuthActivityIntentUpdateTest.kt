package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionAuthActivityIntentUpdateTest {
    @Test
    fun `initial valid request becomes active`() {
        val request = request(id = "request-a")

        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = null,
            completedRequestId = null,
            incomingRequest = request,
        )

        assertEquals(request, update.request)
        assertNull(update.completedRequestId)
        assertNull(update.requestToCancel)
        assertFalse(update.shouldFinish)
    }

    @Test
    fun `different incoming request replaces current and cancels unfinished one`() {
        val requestA = request(id = "request-a")
        val requestB = request(id = "request-b")

        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = requestA,
            completedRequestId = null,
            incomingRequest = requestB,
        )

        assertEquals(requestB, update.request)
        assertNull(update.completedRequestId)
        assertEquals(requestA, update.requestToCancel)
        assertFalse(update.shouldFinish)
    }

    @Test
    fun `same request does not trigger duplicate cancellation`() {
        val request = request(id = "request-a")

        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = request,
            completedRequestId = null,
            incomingRequest = request,
        )

        assertEquals(request, update.request)
        assertNull(update.requestToCancel)
        assertFalse(update.shouldFinish)
    }

    @Test
    fun `completed request is reset when a different request arrives`() {
        val requestA = request(id = "request-a")
        val requestB = request(id = "request-b")

        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = requestA,
            completedRequestId = requestA.requestId,
            incomingRequest = requestB,
        )

        assertEquals(requestB, update.request)
        assertNull(update.completedRequestId)
        assertNull(update.requestToCancel)
        assertFalse(update.shouldFinish)
    }

    @Test
    fun `invalid incoming request clears state finishes and cancels unfinished request`() {
        val request = request(id = "request-a")

        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = request,
            completedRequestId = null,
            incomingRequest = null,
        )

        assertNull(update.request)
        assertNull(update.completedRequestId)
        assertEquals(request, update.requestToCancel)
        assertTrue(update.shouldFinish)
    }
}

private fun request(
    id: String,
    provider: CompanionAuthProvider = CompanionAuthProvider.BITWARDEN,
) = CompanionAuthActivity.Request(
    requestId = id,
    provider = provider,
)
