package com.artemchep.keyguard.common.service.agent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentIpcAuthenticatorTest {
    private val token = ByteArray(AgentIpcAuthenticator.TOKEN_SIZE) { it.toByte() }

    @Test
    fun acceptsTheLaunchTokenAtTheCurrentRevision() {
        val authenticator = AgentIpcAuthenticator(token)
        assertEquals(AgentIpcAuthentication.ACCEPTED, authenticator.check(request()))
    }

    @Test
    fun reportsARevisionMismatchBeforeATokenMismatch() {
        val authenticator = AgentIpcAuthenticator(token)
        for (revision in listOf(0, AgentIpcMessages.PROTOCOL_REVISION + 1)) {
            assertEquals(
                AgentIpcAuthentication.REVISION_MISMATCH,
                authenticator.check(request(token = ByteArray(32) { 9 }, revision = revision)),
            )
        }
        for (supplied in listOf(ByteArray(32) { 9 }, byteArrayOf(), token.copyOf(31))) {
            assertEquals(AgentIpcAuthentication.TOKEN_MISMATCH, authenticator.check(request(token = supplied)))
        }
    }

    @Test
    fun keepsItsOwnCopyAndLeavesTheSuppliedToken() {
        val launch = token.copyOf()
        val authenticator = AgentIpcAuthenticator(launch)
        launch.fill(0)
        val request = request()
        assertEquals(AgentIpcAuthentication.ACCEPTED, authenticator.check(request))
        assertContentEquals(token, request.token)
    }

    @Test
    fun rejectsEveryTokenOnceClosed() {
        val authenticator = AgentIpcAuthenticator(token)
        authenticator.close()
        for (supplied in listOf(token.copyOf(), ByteArray(token.size))) {
            assertEquals(AgentIpcAuthentication.TOKEN_MISMATCH, authenticator.check(request(token = supplied)))
        }
    }

    @Test
    fun requiresAFullSizeToken() {
        assertFailsWith<IllegalArgumentException> { AgentIpcAuthenticator(ByteArray(16)) }
    }

    private fun request(
        token: ByteArray = this.token.copyOf(),
        revision: Int = AgentIpcMessages.PROTOCOL_REVISION,
    ) = AgentIpcMessages.AuthenticateRequest(token = token, protocolRevision = revision)
}
