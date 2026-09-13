package com.artemchep.keyguard.common.service.gpgagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GpgAgentPacketSessionTest {
    private val token = ByteArray(32) { it.toByte() }

    @Test
    fun unauthenticatedRequestsNeverReachProcessor() = runTest {
        val processor = Processor()
        val session = GpgAgentPacketSession(processor, token)
        try {
            val reply = session.process(encode(GpgAgentMessages.IpcRequest(
                id = 7,
                listKeys = GpgAgentMessages.ListKeysRequest(),
            )))
            assertEquals(7L, decode(reply).id)
            assertEquals(GpgAgentMessages.ErrorCode.NOT_AUTHENTICATED, decode(reply).error?.code)
            assertFalse(reply.close)
            assertEquals(0, processor.calls)
        } finally {
            session.close()
        }
    }

    @Test
    fun wrongTokenOrRevisionReturnsFinalAuthenticationFailure() = runTest {
        for (request in listOf(
            authentication(token = ByteArray(32) { 99 }),
            authentication(revision = GpgAgentMessages.PROTOCOL_REVISION + 1),
        )) {
            val session = GpgAgentPacketSession(Processor(), token)
            val reply = session.process(encode(request))
            assertEquals(false, decode(reply).authenticate?.success)
            assertEquals(GpgAgentMessages.PROTOCOL_REVISION, decode(reply).authenticate?.protocolRevision)
            assertTrue(reply.close)
            assertFailsWith<IllegalStateException> { session.process(encode(authentication())) }
        }
    }

    @Test
    fun copiedTokenSurvivesCallerErasureAndOperationsKeepTheirPayloads() = runTest {
        val processor = Processor()
        val supplied = token.copyOf()
        val session = GpgAgentPacketSession(processor, supplied)
        supplied.fill(0)
        try {
            assertEquals(true, decode(session.process(encode(authentication()))).authenticate?.success)
            val hash = byteArrayOf(1, 2, 3)
            val ciphertext = byteArrayOf(4, 5, 6)
            val signing = session.process(encode(GpgAgentMessages.IpcRequest(
                id = 12,
                signHash = GpgAgentMessages.SignHashRequest(
                    keygrip = "sign-key",
                    hashAlgorithm = "sha256",
                    hash = hash,
                ),
            )))
            val decryption = session.process(encode(GpgAgentMessages.IpcRequest(
                id = 13,
                pkdecrypt = GpgAgentMessages.PkdecryptRequest(
                    keygrip = "decrypt-key",
                    ciphertext = ciphertext,
                    unwrapEcdh = true,
                ),
            )))
            assertEquals(12L, decode(signing).id)
            assertEquals("signature", decode(signing).signHash?.sexp)
            assertEquals("sign-key", processor.sign?.keygrip)
            assertContentEquals(hash, processor.sign?.hash)
            assertEquals(13L, decode(decryption).id)
            assertEquals("value", decode(decryption).pkdecrypt?.valueSexp)
            assertEquals("decrypt-key", processor.decrypt?.keygrip)
            assertContentEquals(ciphertext, processor.decrypt?.ciphertext)
            assertEquals(true, processor.decrypt?.unwrapEcdh)
            assertFalse(signing.close)
            assertFalse(decryption.close)
        } finally {
            session.close()
        }
    }

    @Test
    fun processorCanSuspendUntilApprovalWithoutBlockingPacketTransport() = runTest {
        val approval = CompletableDeferred<Unit>()
        val processor = Processor(beforeSign = { approval.await() })
        val session = GpgAgentPacketSession(processor, token)
        try {
            session.process(encode(authentication()))
            val response = async {
                session.process(encode(GpgAgentMessages.IpcRequest(
                    id = 3,
                    signHash = GpgAgentMessages.SignHashRequest(),
                )))
            }
            runCurrent()
            assertFalse(response.isCompleted)
            approval.complete(Unit)
            assertEquals("signature", decode(response.await()).signHash?.sexp)
        } finally {
            session.close()
        }
    }

    @Test
    fun malformedAuthenticationCannotAuthenticateOrDispatchItsSecondVariant() = runTest {
        val processor = Processor()
        val session = GpgAgentPacketSession(processor, token)
        val reply = session.process(encode(authentication().copy(
            listKeys = GpgAgentMessages.ListKeysRequest(),
        )))
        assertEquals(GpgAgentMessages.ErrorCode.UNSPECIFIED, decode(reply).error?.code)
        assertTrue(reply.close)
        assertEquals(0, processor.calls)
        assertFailsWith<IllegalStateException> { session.process(encode(authentication())) }
    }

    @Test
    fun invalidPayloadsCloseSessionBeforeDispatch() = runTest {
        for (packet in listOf(
            byteArrayOf(),
            byteArrayOf(0xff.toByte()),
            ByteArray(GpgAgentPacketSession.MAX_PACKET_SIZE + 1),
        )) {
            val processor = Processor()
            val session = GpgAgentPacketSession(processor, token)
            assertFailsWith<Exception> { session.process(packet) }
            assertEquals(0, processor.calls)
            assertFailsWith<IllegalStateException> { session.process(encode(authentication())) }
        }
    }

    private fun authentication(
        token: ByteArray = this.token,
        revision: Int = GpgAgentMessages.PROTOCOL_REVISION,
    ) = GpgAgentMessages.IpcRequest(
        id = 1,
        authenticate = GpgAgentMessages.AuthenticateRequest(token = token, protocolRevision = revision),
    )

    private fun encode(request: GpgAgentMessages.IpcRequest) = ProtoBuf.encodeToByteArray(request)

    private fun decode(reply: GpgAgentPacketReply): GpgAgentMessages.IpcResponse =
        ProtoBuf.decodeFromByteArray(reply.packet)

    private class Processor(
        private val beforeSign: suspend () -> Unit = {},
    ) : GpgAgentRequestProcessor {
        var calls = 0
        var sign: GpgAgentMessages.SignHashRequest? = null
        var decrypt: GpgAgentMessages.PkdecryptRequest? = null

        override suspend fun listKeys(
            caller: GpgAgentMessages.CallerIdentity?,
        ): GpgAgentRequestProcessor.ListKeysResult {
            calls++
            return GpgAgentRequestProcessor.ListKeysResult.Success(GpgAgentMessages.ListKeysResponse())
        }

        override suspend fun signHash(
            request: GpgAgentMessages.SignHashRequest,
        ): GpgAgentRequestProcessor.GpgAgentOperationResult<
            GpgAgentMessages.SignHashResponse,
        > {
            calls++
            sign = request
            beforeSign()
            return GpgAgentRequestProcessor.GpgAgentOperationResult.Success(
                GpgAgentMessages.SignHashResponse(sexp = "signature"),
            )
        }

        override suspend fun decrypt(
            request: GpgAgentMessages.PkdecryptRequest,
        ): GpgAgentRequestProcessor.GpgAgentOperationResult<
            GpgAgentMessages.PkdecryptResponse,
        > {
            calls++
            decrypt = request
            return GpgAgentRequestProcessor.GpgAgentOperationResult.Success(
                GpgAgentMessages.PkdecryptResponse(valueSexp = "value"),
            )
        }
    }
}
