package com.artemchep.keyguard.android.ipc

import android.content.Intent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_operation_openpgp_other
import com.artemchep.keyguard.res.ipc_protocol_openpgp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidIpcApprovalStoreTest {
    @Test
    fun `authorization is single use and bound to the complete caller identity`() {
        var now = 1_000L
        var nextToken = 0
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 4,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { now },
            randomToken = { "token-${nextToken++}" },
        )
        val caller = caller()

        fun approve(): String {
            val request = request(
                id = "request-$nextToken",
                caller = caller,
                expiresAt = now + 60_000L,
            )
            assertTrue(store.add(request))
            return assertNotNull(
                store.approve(request.id, setOf("key-1")),
            ).token
        }

        val replayToken = approve()
        assertEquals(
            setOf("key-1"),
            store.consume(
                replayToken,
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            )?.approvedKeyIds,
        )
        assertNull(
            store.consume(replayToken, caller, PROTOCOL, ACTION, DIGEST, null),
        )

        assertNull(
            store.consume(
                approve(),
                caller.copy(pid = caller.pid + 1),
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            ),
        )
        assertNull(
            store.consume(
                approve(),
                caller.copy(certificateDigests = listOf("changed-certificate")),
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            ),
        )
        assertNull(
            store.consume(
                approve(),
                caller,
                PROTOCOL,
                "other-action",
                DIGEST,
                null,
            ),
        )
        assertNull(
            store.consume(
                approve(),
                caller,
                PROTOCOL,
                ACTION,
                "other-digest",
                null,
            ),
        )

        val expiredToken = approve()
        now += 60_001L
        assertNull(
            store.consume(
                expiredToken,
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            ),
        )
    }

    @Test
    fun `pending requests expire and obey the configured bound`() {
        var now = 10L
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60L,
            maxPendingRequests = 2,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { now },
            randomToken = { "token" },
        )
        val caller = caller()
        val first = request("first", caller, expiresAt = 20L)
        val second = request("second", caller, expiresAt = 100L)
        val third = request("third", caller, expiresAt = 100L)

        assertTrue(store.add(first))
        assertTrue(store.add(second))
        assertFalse(store.add(third))
        assertFalse(store.add(first))

        now = 21L
        assertNull(store.get(first.id))
        assertTrue(store.add(third))

        store.deny(second.id)
        assertNull(store.get(second.id))
        store.invalidateAll()
        assertNull(store.get(third.id))
    }

    @Test
    fun `unconsumed grants count against the configured bound`() {
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 1,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { 10L },
            randomToken = { "token" },
        )
        val caller = caller()
        val first = request("first", caller, expiresAt = 100L)
        val second = request("second", caller, expiresAt = 100L)

        assertTrue(store.add(first))
        assertNotNull(store.approve(first.id, setOf("key-1")))
        assertFalse(store.add(second))
        assertNotNull(
            store.consume("token", caller, PROTOCOL, ACTION, DIGEST, null),
        )
        assertTrue(store.add(second))
    }

    @Test
    fun `verification authorization can bind an explicitly empty key set`() {
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 1,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { 10L },
            randomToken = { "empty-token" },
        )
        val caller = caller()
        val request = request("verification", caller, expiresAt = 100L)

        assertTrue(store.add(request))
        assertNotNull(store.approve(request.id, emptySet()))
        assertEquals(
            emptySet(),
            store.consume(
                "empty-token",
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            )?.approvedKeyIds,
        )
    }

    @Test
    fun `approved tokenless retry is one shot and request bound`() {
        var now = 10L
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 2,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { now },
            randomToken = { "tokenless-grant" },
        )
        val caller = caller()
        val request = request(
            id = "autocrypt-selection",
            caller = caller,
            expiresAt = 60_010L,
            allowTokenlessRetry = true,
        )
        assertTrue(store.add(request))
        assertNotNull(store.approve(request.id, setOf("key-1")))

        assertNull(
            store.consume(
                null,
                caller,
                PROTOCOL,
                ACTION,
                "different-request",
                null,
            ),
        )
        assertEquals(
            setOf("key-1"),
            store.consume(
                null,
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                null,
            )?.approvedKeyIds,
        )
        assertNull(
            store.consume(null, caller, PROTOCOL, ACTION, DIGEST, null),
        )

        val expiring = request(
            id = "expiring-autocrypt-selection",
            caller = caller,
            expiresAt = 60_010L,
            allowTokenlessRetry = true,
        )
        assertTrue(store.add(expiring))
        assertNotNull(store.approve(expiring.id, setOf("key-1")))
        now += 60_001L
        assertNull(
            store.consume(null, caller, PROTOCOL, ACTION, DIGEST, null),
        )
    }

    @Test
    fun `private authorization is bound to session and protocol scoped clearing`() {
        var sessionIdentity = "session-1"
        var tokenIndex = 0
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 4,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { 10L },
            randomToken = { "token-${tokenIndex++}" },
        )
        val caller = caller()
        val privateRequest = request(
            id = "private",
            caller = caller,
            expiresAt = 100L,
            requiresAuthentication = true,
            sessionIdentity = { sessionIdentity },
        )
        assertTrue(store.add(privateRequest))
        val privateToken = assertNotNull(
            store.approve(privateRequest.id, setOf("key-1")),
        ).token
        sessionIdentity = "session-2"
        assertNull(
            store.consume(
                privateToken,
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                sessionIdentity,
            ),
        )

        val sshRequest = request(
            id = "ssh",
            caller = caller,
            expiresAt = 100L,
            protocol = "ssh",
        )
        val openPgpRequest = request(
            id = "openpgp",
            caller = caller,
            expiresAt = 100L,
            protocol = "openpgp",
        )
        assertTrue(store.add(sshRequest))
        assertTrue(store.add(openPgpRequest))
        store.invalidateProtocol("ssh")
        assertNull(store.get(sshRequest.id))
        assertNotNull(store.get(openPgpRequest.id))
    }

    @Test
    fun `session changes clear issued private grants but preserve pending unlock requests`() {
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 2,
            maxPendingRequestsPerCaller = 8,
            elapsedNow = { 10L },
            randomToken = { "private-token" },
        )
        val caller = caller()
        val pendingRequest = request(
            id = "pending",
            caller = caller,
            expiresAt = 100L,
            requiresAuthentication = true,
            sessionIdentity = { "unlocked-session" },
        )
        val approvedRequest = request(
            id = "approved",
            caller = caller,
            expiresAt = 100L,
            requiresAuthentication = true,
            sessionIdentity = { "unlocked-session" },
        )
        assertTrue(store.add(pendingRequest))
        assertTrue(store.add(approvedRequest))
        assertNotNull(store.approve(approvedRequest.id, setOf("key-1")))

        store.invalidatePrivateGrants()

        assertNotNull(store.get(pendingRequest.id))
        assertNull(
            store.consume(
                "private-token",
                caller,
                PROTOCOL,
                ACTION,
                DIGEST,
                "unlocked-session",
            ),
        )
    }

    @Test
    fun `per caller bound rejects a flooding principal without starving others`() {
        var now = 10L
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60L,
            maxPendingRequests = 8,
            maxPendingRequestsPerCaller = 2,
            elapsedNow = { now },
            randomToken = { "token" },
        )
        val flooding = caller()
        assertTrue(store.add(request("first", flooding, expiresAt = 20L)))
        assertTrue(store.add(request("second", flooding, expiresAt = 100L)))
        assertFalse(store.add(request("third", flooding, expiresAt = 100L)))

        // The quota is keyed by the principal, so a restarted client
        // process is still the same caller...
        val restarted = flooding.copy(uid = flooding.uid + 1, pid = flooding.pid + 1)
        assertFalse(store.add(request("restarted", restarted, expiresAt = 100L)))
        // ...while a different package or signer is not.
        val otherPackage = flooding.copy(packageName = "example.other")
        assertTrue(store.add(request("other-package", otherPackage, expiresAt = 100L)))
        val otherSigner = flooding.copy(certificateDigests = listOf("other"))
        assertTrue(store.add(request("other-signer", otherSigner, expiresAt = 100L)))

        now = 21L
        assertTrue(store.add(request("third", flooding, expiresAt = 100L)))
        store.deny("second")
        assertTrue(store.add(request("fourth", flooding, expiresAt = 100L)))
        assertFalse(store.add(request("fifth", flooding, expiresAt = 100L)))
    }

    @Test
    fun `issued grants do not consume the per caller bound`() {
        var tokenIndex = 0
        val store = AndroidIpcApprovalStore(
            lifetimeMs = 60_000L,
            maxPendingRequests = 8,
            maxPendingRequestsPerCaller = 1,
            elapsedNow = { 10L },
            randomToken = { "token-${tokenIndex++}" },
        )
        val caller = caller()
        assertTrue(store.add(request("first", caller, expiresAt = 100L)))
        assertNotNull(store.approve("first", setOf("key-1")))
        assertTrue(store.add(request("second", caller, expiresAt = 100L)))
        assertNotNull(store.approve("second", setOf("key-1")))
        assertTrue(store.add(request("third", caller, expiresAt = 100L)))
    }

    private fun caller() = AndroidIpcCaller(
        uid = 10001,
        pid = 4321,
        packageName = "example.client",
        appLabel = "Example Client",
        certificateDigests = listOf("current", "historical"),
    )

    private fun request(
        id: String,
        caller: AndroidIpcCaller,
        expiresAt: Long,
        protocol: String = PROTOCOL,
        requiresAuthentication: Boolean = false,
        allowTokenlessRetry: Boolean = false,
        sessionIdentity: () -> String? = { null },
    ) = AndroidIpcApprovalCoordinator.Request(
        id = id,
        caller = caller,
        protocol = protocol,
        protocolLabel = Res.string.ipc_protocol_openpgp,
        action = ACTION,
        operation = Res.string.ipc_operation_openpgp_other,
        requestDigest = DIGEST,
        retryIntent = Intent(ACTION),
        allowMultiple = false,
        requiresAuthentication = requiresAuthentication,
        allowTokenlessRetry = allowTokenlessRetry,
        sessionIdentity = sessionIdentity,
        loadCandidates = { emptyList() },
        expiresAtElapsedMs = expiresAt,
    )

    private companion object {
        const val ACTION = "example.action"
        const val DIGEST = "request-digest"
        const val PROTOCOL = "test"
    }
}
