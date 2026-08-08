@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val allNativeCryptoCapabilitiesMask: Long = NativeCryptoCapability.entries
    .fold(0L) { mask, capability -> mask or capability.bit }

class NativeCryptoClientTest {
    @Test
    fun rejectsAbiMismatch() {
        val bridge = FakeBridge(abiVersion = NativeCrypto.EXPECTED_ABI_VERSION + 1)
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).ensureReady()
        }

        assertEquals(NativeCryptoErrorCode.ABI_MISMATCH, exception.code)
        assertEquals("bootstrap.abi", exception.operation)
    }

    @Test
    fun rejectsMissingCapabilities() {
        val bridge = FakeBridge(capabilities = 0L)
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).ensureReady()
        }

        assertEquals(NativeCryptoErrorCode.MISSING_CAPABILITY, exception.code)
        assertEquals("bootstrap.capabilities", exception.operation)
    }

    @Test
    fun rejectsRuntimeMissingLockstepExtensionCapabilities() {
        listOf(
            NativeCryptoCapability.SSH_PUBLIC_KEY_DECODE,
            NativeCryptoCapability.OPENPGP_CLEAR_VERIFY,
        ).forEach { missingCapability ->
            val bridge = FakeBridge(
                capabilities = allNativeCryptoCapabilitiesMask and missingCapability.bit.inv(),
            )
            val exception = assertFailsWith<NativeCryptoException> {
                NativeCryptoClient(bridge).ensureReady()
            }

            assertEquals(NativeCryptoErrorCode.MISSING_CAPABILITY, exception.code)
            assertEquals("bootstrap.capabilities", exception.operation)
            assertNull(bridge.lastCallRequest)
            assertNull(bridge.lastStreamOpenRequest)
        }
    }

    @Test
    fun ignoresUnknownFutureCapabilityBits() {
        val client = NativeCryptoClient(
            FakeBridge(capabilities = allNativeCryptoCapabilitiesMask or (1L shl 62)),
        )

        client.ensureReady()

        assertEquals(NativeCryptoCapability.entries.toSet(), client.capabilities)
    }

    @Test
    fun rejectsMalformedResponse() {
        val bridge = FakeBridge(callResponse = byteArrayOf(0x80.toByte()))
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("digest", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, exception.code)
        assertEquals("digest", exception.operation)
    }

    @Test
    fun publicExceptionDoesNotRetainPlatformDiagnostics() {
        val sensitiveDiagnostic = "/private/secret/native/library/path"
        val bridge = FakeBridge(
            callFailure = NativeCryptoPlatformException(
                NativeCryptoErrorCode.LIBRARY_UNAVAILABLE,
                IllegalStateException(sensitiveDiagnostic),
            ),
        )

        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("digest", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, exception.code)
        assertEquals(
            "Native crypto failed: operation=digest, code=LIBRARY_UNAVAILABLE",
            exception.message,
        )
        assertNull(exception.cause)
        assertFalse(exception.toString().contains(sensitiveDiagnostic))
    }

    @Test
    fun clearsCallRequestAndResponseEnvelopesAfterSuccess() {
        val bridge = FakeBridge(
            callResponse = response(BytesResultProto(byteArrayOf(7, 8))),
        )

        val result = NativeCryptoClient(bridge).call("digest", digestOperation())

        assertContentEquals(byteArrayOf(7, 8), (result as BytesResultProto).value)
        assertTrue(assertNotNull(bridge.lastCallRequest).all { byte -> byte == 0.toByte() })
        assertTrue(assertNotNull(bridge.lastCallResponse).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsCallRequestEnvelopeAfterBridgeFailure() {
        val bridge = FakeBridge(callFailure = IllegalStateException("sensitive diagnostic"))

        assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("digest", digestOperation())
        }

        assertTrue(assertNotNull(bridge.lastCallRequest).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsResponseEnvelopeAfterNativeError() {
        val bridge = FakeBridge(
            callResponse = response(code = NativeErrorCodeProto.CRYPTO_FAILURE),
        )

        assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("digest", digestOperation())
        }

        assertTrue(assertNotNull(bridge.lastCallResponse).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsDecodedByteResultAfterNativeError() {
        var discardedOutput: ByteArray? = null
        val bridge = FakeBridge(
            callResponse = response(
                result = BytesResultProto(byteArrayOf(7, 8, 9)),
                code = NativeErrorCodeProto.CRYPTO_FAILURE,
            ),
        )
        val client = NativeCryptoClient(
            bridge = bridge,
            onDiscardedOutputCleared = { output -> discardedOutput = output },
        )

        val exception = assertFailsWith<NativeCryptoException> {
            client.call("digest", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.CRYPTO_FAILURE, exception.code)
        assertContentEquals(ByteArray(3), discardedOutput)
        assertTrue(assertNotNull(bridge.lastCallResponse).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsByteResultReturnedForNonByteResultType() {
        var discardedOutput: ByteArray? = null
        val bridge = FakeBridge(
            callResponse = response(BytesResultProto(byteArrayOf(7, 8, 9))),
        )
        val client = NativeCryptoClient(
            bridge = bridge,
            onDiscardedOutputCleared = { output -> discardedOutput = output },
        )

        val exception = assertFailsWith<NativeCryptoException> {
            client.callInt32("ssh_private_key_rsa_bits", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, exception.code)
        assertContentEquals(ByteArray(3), discardedOutput)
    }

    @Test
    fun clearsByteResultReturnedAsSessionHandle() {
        var discardedOutput: ByteArray? = null
        val bridge = FakeBridge(
            streamOpenResponse = response(BytesResultProto(byteArrayOf(7, 8, 9))),
        )
        val client = NativeCryptoClient(
            bridge = bridge,
            onDiscardedOutputCleared = { output -> discardedOutput = output },
        )

        val exception = assertFailsWith<NativeCryptoException> {
            client.openHmacSha256(byteArrayOf(7))
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, exception.code)
        assertContentEquals(ByteArray(3), discardedOutput)
    }

    @Test
    fun decodesDirectRandomIntWithoutAProtobufEnvelope() {
        val bridge = FakeBridge(
            fastRandomInt = { exclusiveUpperBound ->
                assertEquals(0, exclusiveUpperBound)
                packNativeCryptoIntResult(0, Int.MIN_VALUE)
            },
        )

        assertEquals(Int.MIN_VALUE, NativeCryptoClient(bridge).randomInt(0))
    }

    @Test
    fun mapsDirectRandomIntErrorsAndRejectsOutOfRangeValues() {
        val failureBridge = FakeBridge(
            fastRandomInt = {
                packNativeCryptoIntResult(
                    NativeCryptoErrorCode.CRYPTO_FAILURE.wireValue!!,
                    0,
                )
            },
        )
        val failure = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(failureBridge).randomInt(10)
        }
        assertEquals(NativeCryptoErrorCode.CRYPTO_FAILURE, failure.code)
        assertEquals("random_int", failure.operation)

        val malformedBridge = FakeBridge(
            fastRandomInt = { packNativeCryptoIntResult(0, 10) },
        )
        val malformed = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(malformedBridge).randomInt(10)
        }
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, malformed.code)
        assertEquals("random_int", malformed.operation)
    }

    @Test
    fun acceptsFastCallerOwnedOutputsWithAnExactPackedLength() {
        val bridge = FakeBridge(
            fastEncrypt = { ciphertext, mac ->
                ciphertext.fill(0x31.toByte())
                mac.fill(0x52.toByte())
                packNativeCryptoFastResult(0, ciphertext.size)
            },
        )
        val ciphertext = ByteArray(16)
        val mac = ByteArray(32)

        val length = NativeCryptoClient(bridge).aesCbcPkcs7HmacSha256EncryptFast(
            encryptionKey = ByteArray(32),
            macKey = ByteArray(32),
            iv = ByteArray(16),
            plaintext = ByteArray(0),
            ciphertextOutput = ciphertext,
            macOutput = mac,
        )

        assertEquals(16, length)
        assertTrue(ciphertext.all { byte -> byte == 0x31.toByte() })
        assertTrue(mac.all { byte -> byte == 0x52.toByte() })
    }

    @Test
    fun mapsFastErrorsAndMalformedLengthsAndClearsCallerOutputs() {
        val authenticationBridge = FakeBridge(
            fastDecrypt = { plaintext ->
                plaintext.fill(0x73.toByte())
                packNativeCryptoFastResult(
                    NativeCryptoErrorCode.AUTHENTICATION_FAILED.wireValue!!,
                    0,
                )
            },
        )
        val plaintext = ByteArray(16) { 0x44.toByte() }
        val authenticationFailure = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(authenticationBridge).aesCbcPkcs7HmacSha256DecryptFast(
                encryptionKey = ByteArray(32),
                macKey = ByteArray(32),
                iv = ByteArray(16),
                ciphertext = ByteArray(16),
                expectedMac = ByteArray(32),
                plaintextOutput = plaintext,
            )
        }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, authenticationFailure.code)
        assertTrue(plaintext.all { byte -> byte == 0.toByte() })

        val malformedBridge = FakeBridge(
            fastEncrypt = { ciphertext, mac ->
                ciphertext.fill(0x31.toByte())
                mac.fill(0x52.toByte())
                packNativeCryptoFastResult(0, ciphertext.size + 1)
            },
        )
        val ciphertext = ByteArray(16) { 0x44.toByte() }
        val mac = ByteArray(32) { 0x55.toByte() }
        val malformed = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(malformedBridge).aesCbcPkcs7HmacSha256EncryptFast(
                encryptionKey = ByteArray(32),
                macKey = ByteArray(32),
                iv = ByteArray(16),
                plaintext = ByteArray(0),
                ciphertextOutput = ciphertext,
                macOutput = mac,
            )
        }
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, malformed.code)
        assertTrue(ciphertext.all { byte -> byte == 0.toByte() })
        assertTrue(mac.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsStreamOpenRequestEnvelopeAfterSuccessAndFailure() {
        val successBridge = FakeBridge(streamOpenResponse = response(UInt64ResultProto(42L)))
        val session = NativeCryptoClient(successBridge).openHmacSha256(byteArrayOf(7))
        assertTrue(assertNotNull(successBridge.lastStreamOpenRequest).all { byte -> byte == 0.toByte() })
        assertTrue(assertNotNull(successBridge.lastStreamOpenResponse).all { byte -> byte == 0.toByte() })
        session.close()

        val failureBridge = FakeBridge(
            streamOpenFailure = IllegalStateException("sensitive diagnostic"),
        )
        assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(failureBridge).openHmacSha256(byteArrayOf(7))
        }
        assertTrue(assertNotNull(failureBridge.lastStreamOpenRequest).all { byte -> byte == 0.toByte() })
    }

    @Test
    fun mapsNativeErrorWithoutTrustingResponseOperation() {
        val bridge = FakeBridge(
            callResponse = response(
                code = NativeErrorCodeProto.INVALID_ARGUMENT,
                operation = "untrusted-response-operation",
            ),
        )
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("digest", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, exception.code)
        assertEquals("digest", exception.operation)
    }

    @Test
    fun mapsUnsupportedOpenPgpKeyVersionError() {
        val bridge = FakeBridge(
            callResponse = response(
                code = NativeErrorCodeProto.UNSUPPORTED_KEY_VERSION,
            ),
        )
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("open_pgp_decrypt", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.UNSUPPORTED_KEY_VERSION, exception.code)
        assertEquals("open_pgp_decrypt", exception.operation)
    }

    @Test
    fun mapsNoUsableOpenPgpKeyError() {
        val bridge = FakeBridge(
            callResponse = response(
                code = NativeErrorCodeProto.NO_USABLE_KEY,
            ),
        )
        val exception = assertFailsWith<NativeCryptoException> {
            NativeCryptoClient(bridge).call("open_pgp_encrypt", digestOperation())
        }

        assertEquals(NativeCryptoErrorCode.NO_USABLE_KEY, exception.code)
        assertEquals("open_pgp_encrypt", exception.operation)
    }

    @Test
    fun finishClosesSessionAndPreventsDoubleUse() {
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamFinishResponse = response(BytesResultProto(byteArrayOf(1, 2, 3))),
        )
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        assertContentEquals(byteArrayOf(1, 2, 3), session.finish())
        assertEquals(1, bridge.streamFinishCalls)
        assertEquals(1, bridge.streamCloseCalls)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals("stream.finish", finishReuse.operation)
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
        val updateReuse = assertFailsWith<NativeCryptoException> { session.update(byteArrayOf(1)) }
        assertEquals("stream.update", updateReuse.operation)
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        session.close()
        assertEquals(1, bridge.streamCloseCalls)
    }

    @Test
    fun closeIsIdempotent() {
        val bridge = FakeBridge(streamOpenResponse = response(UInt64ResultProto(42L)))
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        session.close()
        session.close()

        assertEquals(1, bridge.streamCloseCalls)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals("stream.finish", finishReuse.operation)
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
        val updateReuse = assertFailsWith<NativeCryptoException> { session.update(byteArrayOf(1)) }
        assertEquals("stream.update", updateReuse.operation)
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
    }

    @Test
    fun failedCloseCanBeRetriedWithoutPermittingSessionReuse() {
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamCloseResponses = listOf(
                response(code = NativeErrorCodeProto.INTERNAL),
                response(BytesResultProto(ByteArray(0))),
            ),
        )
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        val failure = assertFailsWith<NativeCryptoException> { session.close() }
        assertEquals(NativeCryptoErrorCode.INTERNAL, failure.code)
        val updateReuse = assertFailsWith<NativeCryptoException> {
            session.update(byteArrayOf(1))
        }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)

        session.close()
        session.close()
        assertEquals(2, bridge.streamCloseCalls)
    }

    @Test
    fun rejectsUnexpectedSuccessfulClosePayloadAndAllowsCleanupRetry() {
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamCloseResponses = listOf(
                response(BytesResultProto(byteArrayOf(1, 2, 3))),
                response(BytesResultProto(ByteArray(0))),
            ),
        )
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        val failure = assertFailsWith<NativeCryptoException> { session.close() }
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(assertNotNull(bridge.lastStreamCloseResponse).all { byte -> byte == 0.toByte() })

        session.close()
        assertEquals(2, bridge.streamCloseCalls)
    }

    @Test
    fun finishPreservesPrimaryFailureWhenCloseAlsoFails() {
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamFinishResponse = response(code = NativeErrorCodeProto.CRYPTO_FAILURE),
            streamCloseResponse = response(code = NativeErrorCodeProto.INVALID_SESSION),
        )
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        val exception = assertFailsWith<NativeCryptoException> { session.finish() }

        assertEquals(NativeCryptoErrorCode.CRYPTO_FAILURE, exception.code)
        assertEquals(1, bridge.streamCloseCalls)
        assertEquals(1, exception.suppressedExceptions.size)
        assertEquals(
            NativeCryptoErrorCode.INVALID_SESSION,
            (exception.suppressedExceptions.single() as NativeCryptoException).code,
        )
    }

    @Test
    fun finishPropagatesCloseFailureAfterSuccessfulFinalization() {
        var discardedOutput: ByteArray? = null
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamFinishResponse = response(BytesResultProto(byteArrayOf(1, 2, 3))),
            streamCloseResponses = listOf(
                response(code = NativeErrorCodeProto.INVALID_SESSION),
                response(BytesResultProto(ByteArray(0))),
            ),
        )
        val session = NativeCryptoClient(
            bridge = bridge,
            onDiscardedOutputCleared = { output -> discardedOutput = output },
        ).openHmacSha256(byteArrayOf(7))

        val exception = assertFailsWith<NativeCryptoException> { session.finish() }

        assertEquals("stream.close", exception.operation)
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, exception.code)
        assertEquals(1, bridge.streamFinishCalls)
        assertEquals(1, bridge.streamCloseCalls)
        assertContentEquals(ByteArray(3), discardedOutput)
        assertTrue(assertNotNull(bridge.lastStreamFinishResponse).all { byte -> byte == 0.toByte() })

        session.close()
        assertEquals(2, bridge.streamCloseCalls)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
    }

    @Test
    fun clearsOwnedSliceAfterUpdate() {
        val bridge = FakeBridge(streamOpenResponse = response(UInt64ResultProto(42L)))
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        session.update(byteArrayOf(9, 8, 7, 6), offset = 1, length = 2)

        assertContentEquals(byteArrayOf(0, 0), bridge.lastStreamInput)
        session.close()
    }

    @Test
    fun rejectsStreamChunksLargerThan64KiB() {
        val bridge = FakeBridge(streamOpenResponse = response(UInt64ResultProto(42L)))
        val session = NativeCryptoClient(bridge).openHmacSha256(byteArrayOf(7))

        val exception = assertFailsWith<NativeCryptoException> {
            session.update(ByteArray(64 * 1024 + 1))
        }

        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, exception.code)
        assertEquals(0, bridge.streamUpdateCalls)
        session.close()
    }

    @Test
    fun preservesOptionalNullVersusEmptySalt() {
        val absent = NativeRequestProto(
            protocolVersion = NativeCrypto.PROTOCOL_VERSION,
            operation = HkdfSha256OperationProto(
                HkdfSha256RequestProto(
                    seed = byteArrayOf(1),
                    salt = null,
                    info = null,
                    length = 1,
                ),
            ),
        )
        val empty = absent.copy(
            operation = HkdfSha256OperationProto(
                (absent.operation as HkdfSha256OperationProto).value.copy(salt = ByteArray(0)),
            ),
        )
        val absentBytes = ProtoBuf.encodeToByteArray(absent)
        val emptyBytes = ProtoBuf.encodeToByteArray(empty)

        assertContentEquals(byteArrayOf(0x08, 0x01), absentBytes.copyOfRange(0, 2))
        assertNotEquals(absentBytes.toList(), emptyBytes.toList())
        val decodedAbsent = ProtoBuf.decodeFromByteArray<NativeRequestProto>(absentBytes)
        val decodedEmpty = ProtoBuf.decodeFromByteArray<NativeRequestProto>(emptyBytes)
        assertEquals(null, (decodedAbsent.operation as HkdfSha256OperationProto).value.salt)
        assertContentEquals(
            ByteArray(0),
            (decodedEmpty.operation as HkdfSha256OperationProto).value.salt,
        )
    }

    @Test
    fun preservesOptionalNullVersusEmptySshImportPassphrase() {
        val absent = NativeRequestProto(
            protocolVersion = NativeCrypto.PROTOCOL_VERSION,
            operation = SshPrivateKeyImportOperationProto(
                SshPrivateKeyImportRequestProto(
                    content = "-----BEGIN PRIVATE KEY-----",
                    passphraseUtf8 = null,
                ),
            ),
        )
        val empty = absent.copy(
            operation = SshPrivateKeyImportOperationProto(
                (absent.operation as SshPrivateKeyImportOperationProto).value.copy(
                    passphraseUtf8 = ByteArray(0),
                ),
            ),
        )

        val absentBytes = ProtoBuf.encodeToByteArray(absent)
        val emptyBytes = ProtoBuf.encodeToByteArray(empty)

        assertNotEquals(absentBytes.toList(), emptyBytes.toList())
        val decodedAbsent = ProtoBuf.decodeFromByteArray<NativeRequestProto>(absentBytes)
        val decodedEmpty = ProtoBuf.decodeFromByteArray<NativeRequestProto>(emptyBytes)
        assertNull(
            (decodedAbsent.operation as SshPrivateKeyImportOperationProto).value.passphraseUtf8,
        )
        assertContentEquals(
            ByteArray(0),
            (decodedEmpty.operation as SshPrivateKeyImportOperationProto).value.passphraseUtf8,
        )
    }

    @Test
    fun roundTripsTypedSshImportOutcomes() {
        val outcomes = listOf<SshPrivateKeyImportOutcomeProto>(
            SshPrivateKeyImportSuccessOutcomeProto(
                SshPrivateKeyImportSuccessProto(
                    keyMaterial = SshKeyMaterialProto(
                        type = SshKeyTypeProto.ED25519,
                        privateKey = byteArrayOf(1),
                        publicKey = byteArrayOf(2),
                    ),
                ),
            ),
            SshPrivateKeyImportNeedsPassphraseOutcomeProto(
                SshPrivateKeyImportNeedsPassphraseProto("OpenSSH"),
            ),
            SshPrivateKeyImportErrorOutcomeProto(
                SshPrivateKeyImportErrorProto(
                    SshPrivateKeyImportErrorReasonProto.INVALID_PASSPHRASE,
                ),
            ),
        )

        outcomes.forEach { outcome ->
            val encoded = ProtoBuf.encodeToByteArray(
                SshPrivateKeyImportResultProto(outcome),
            )
            val decoded = ProtoBuf.decodeFromByteArray<SshPrivateKeyImportResultProto>(encoded)
            assertEquals(outcome::class, decoded.result?.let { it::class })
        }
    }

    @Test
    fun roundTripsTypedOpenPgpOperationsAndResults() {
        val oneShotOperations = listOf<NativeRequestOperationProto>(
            OpenPgpPublicKeyParseOperationProto(
                OpenPgpPublicKeyParseRequestProto(
                    keyData = byteArrayOf(1),
                    referenceTimeEpochSeconds = 1_700_000_000L,
                ),
            ),
            OpenPgpVerifyOperationProto(
                OpenPgpVerifyRequestProto(
                    kind = OpenPgpVerifyKindProto.DETACHED,
                    content = byteArrayOf(2),
                    signature = byteArrayOf(3),
                    publicKeys = listOf(byteArrayOf(4)),
                ),
            ),
            OpenPgpMetadataResolveOperationProto(
                OpenPgpMetadataResolveRequestProto(
                    privateKeyData = byteArrayOf(5),
                    normalizedFingerprint = "AABB",
                    candidateRevocationKeys = listOf(byteArrayOf(6)),
                ),
            ),
        )
        oneShotOperations.forEachIndexed { index, operation ->
            val encoded = ProtoBuf.encodeToByteArray(
                NativeRequestProto(
                    protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                    operation = operation,
                ),
            )
            val decoded = ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded)
            assertEquals(operation::class, decoded.operation::class)
            val wireTag = ((34 + index) shl 3) or 2
            val firstTagByte = ((wireTag and 0x7f) or 0x80).toByte()
            val secondTagByte = (wireTag ushr 7).toByte()
            assertTrue(
                encoded.asList().windowed(2).any { bytes ->
                    bytes[0] == firstTagByte && bytes[1] == secondTagByte
                },
            )
        }

        val parseOutcomes = listOf<OpenPgpPublicKeyParseOutcomeProto>(
            OpenPgpPublicKeyParseSuccessOutcomeProto(
                OpenPgpPublicKeyParseSuccessProto(
                    keys = listOf(
                        OpenPgpPublicKeyInfoProto(
                            fingerprint = "A".repeat(40),
                            keyId = "A".repeat(16),
                            algorithm = "RSA",
                            publicKeyArmored = "public",
                        ),
                    ),
                ),
            ),
            OpenPgpPublicKeyParseErrorOutcomeProto(
                OpenPgpPublicKeyParseErrorProto(
                    OpenPgpPublicKeyParseErrorReasonProto.UNSUPPORTED_KEY_VERSION,
                ),
            ),
        )
        parseOutcomes.forEach { outcome ->
            val encoded = ProtoBuf.encodeToByteArray(OpenPgpPublicKeyParseResultProto(outcome))
            val decoded = ProtoBuf.decodeFromByteArray<OpenPgpPublicKeyParseResultProto>(encoded)
            assertEquals(outcome::class, decoded.result?.let { it::class })
        }

        val verification = OpenPgpVerificationProto(
            status = OpenPgpVerificationStatusProto.VALID,
            keyId = "A".repeat(16),
            fingerprint = "B".repeat(40),
            userIds = listOf("Alice <alice@example.invalid>"),
            createdAtEpochSeconds = 1_700_000_000L,
            warnings = listOf(
                OpenPgpVerificationWarningProto.KEY_REVOKED.wireValue,
                OpenPgpVerificationWarningProto.KEY_EXPIRED.wireValue,
                OpenPgpVerificationWarningProto.SIGNATURE_EXPIRED.wireValue,
            ),
        )
        val verificationBytes = ProtoBuf.encodeToByteArray(verification)
        assertTrue(
            verificationBytes
                .asList()
                .windowed(5)
                .contains(listOf(0x32, 0x03, 0x01, 0x02, 0x03).map(Int::toByte)),
            "proto3 repeated verification warnings must use packed enum encoding",
        )
        assertEquals(
            verification,
            ProtoBuf.decodeFromByteArray<OpenPgpVerificationProto>(
                verificationBytes,
            ),
        )
    }

    @Test
    fun roundTripsTypedOpenPgpWriteOperationsAndFinalDtos() {
        val oneShotOperations = listOf<NativeRequestOperationProto>(
            OpenPgpKeyGenerateOperationProto(
                OpenPgpKeyGenerateRequestProto(
                    kind = OpenPgpKeyKindProto.LEGACY_ED25519_X25519,
                    userId = "Alice <alice@example.invalid>",
                    creationTimeEpochSeconds = 1_700_000_000L,
                    expirationSeconds = 86_400u,
                ),
            ),
            OpenPgpKeyImportOperationProto(
                OpenPgpKeyImportRequestProto(
                    keyData = byteArrayOf(1),
                    passphraseUtf8 = byteArrayOf(),
                    referenceTimeEpochSeconds = 1_700_000_001L,
                ),
            ),
            OpenPgpSignOperationProto(
                OpenPgpSignRequestProto(
                    kind = OpenPgpSignKindProto.DETACHED,
                    content = byteArrayOf(2),
                    privateKey = byteArrayOf(3),
                    preferredFingerprint = "A".repeat(40),
                    armored = true,
                    signatureTimeEpochSeconds = 1_700_000_002L,
                ),
            ),
            OpenPgpEncryptOperationProto(
                OpenPgpEncryptRequestProto(
                    content = byteArrayOf(4),
                    publicKeys = listOf(byteArrayOf(5)),
                    signingPrivateKey = byteArrayOf(6),
                    preferredSigningFingerprint = "B".repeat(40),
                    fileName = "message.txt",
                    armored = false,
                    literalTimeEpochSeconds = 1_700_000_003L,
                ),
            ),
            OpenPgpDecryptOperationProto(
                OpenPgpDecryptRequestProto(
                    content = byteArrayOf(7),
                    privateKeys = listOf(byteArrayOf(8)),
                    verificationPublicKeys = listOf(byteArrayOf(9)),
                    referenceTimeEpochSeconds = 1_700_000_004L,
                ),
            ),
        )
        oneShotOperations.forEachIndexed { index, operation ->
            val encoded = ProtoBuf.encodeToByteArray(
                NativeRequestProto(
                    protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                    operation = operation,
                ),
            )
            val decoded = ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded)
            assertEquals(operation::class, decoded.operation::class)
            assertContainsLengthDelimitedTag(encoded, fieldNumber = 37 + index)
        }

        val streamOperations = listOf<NativeStreamOpenOperationProto>(
            OpenPgpDetachedSignStreamOpenOperationProto(
                OpenPgpDetachedSignStreamOpenRequestProto(
                    privateKey = byteArrayOf(1),
                    armored = true,
                ),
            ),
            OpenPgpEncryptStreamOpenOperationProto(
                OpenPgpEncryptStreamOpenRequestProto(
                    publicKeys = listOf(byteArrayOf(2)),
                    fileName = "payload.bin",
                    armored = false,
                ),
            ),
            OpenPgpDecryptStreamOpenOperationProto(
                OpenPgpDecryptStreamOpenRequestProto(
                    privateKeys = listOf(byteArrayOf(3)),
                    verificationPublicKeys = listOf(byteArrayOf(4)),
                ),
            ),
            AesCbcPkcs7HmacSha256EncryptStreamOpenOperationProto(
                AesCbcPkcs7HmacSha256EncryptStreamOpenRequestProto(
                    encryptionKey = byteArrayOf(5),
                    macKey = byteArrayOf(6),
                    iv = byteArrayOf(7),
                ),
            ),
            AesCbcPkcs7HmacSha256DecryptStreamOpenOperationProto(
                AesCbcPkcs7HmacSha256DecryptStreamOpenRequestProto(
                    encryptionKey = byteArrayOf(8),
                    macKey = byteArrayOf(9),
                    iv = byteArrayOf(10),
                    expectedMac = byteArrayOf(11),
                ),
            ),
        )
        streamOperations.forEachIndexed { index, operation ->
            val encoded = ProtoBuf.encodeToByteArray(
                NativeStreamOpenRequestProto(
                    protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                    operation = operation,
                ),
            )
            val decoded = ProtoBuf.decodeFromByteArray<NativeStreamOpenRequestProto>(encoded)
            assertEquals(operation::class, decoded.operation::class)
            assertContainsLengthDelimitedTag(encoded, fieldNumber = 16 + index)
        }

        val material = OpenPgpKeyMaterialProto(
            privateKeyArmored = byteArrayOf(10),
            publicKeyArmored = byteArrayOf(11),
            fingerprint = "C".repeat(40),
        )
        val decodedMaterial = ProtoBuf.decodeFromByteArray<OpenPgpKeyMaterialProto>(
            ProtoBuf.encodeToByteArray(material),
        )
        assertContentEquals(material.privateKeyArmored, decodedMaterial.privateKeyArmored)
        assertContentEquals(material.publicKeyArmored, decodedMaterial.publicKeyArmored)
        assertEquals(material.fingerprint, decodedMaterial.fingerprint)

        val importOutcomes = listOf<OpenPgpKeyImportOutcomeProto>(
            OpenPgpKeyImportSuccessOutcomeProto(
                OpenPgpKeyImportSuccessProto(keyMaterial = material),
            ),
            OpenPgpKeyImportNeedsPassphraseOutcomeProto(
                OpenPgpKeyImportNeedsPassphraseProto(formatLabel = "OpenPGP"),
            ),
            OpenPgpKeyImportErrorOutcomeProto(
                OpenPgpKeyImportErrorProto(
                    reason = OpenPgpKeyImportErrorReasonProto.INVALID_PASSPHRASE,
                ),
            ),
        )
        importOutcomes.forEach { outcome ->
            val encoded = ProtoBuf.encodeToByteArray(OpenPgpKeyImportResultProto(outcome))
            val decoded = ProtoBuf.decodeFromByteArray<OpenPgpKeyImportResultProto>(encoded)
            assertEquals(outcome::class, decoded.result?.let { it::class })
        }

        val verification = OpenPgpVerificationProto(
            status = OpenPgpVerificationStatusProto.VALID,
            keyId = "D".repeat(16),
            fingerprint = "D".repeat(40),
        )
        val encryptResult = roundTrip(
            OpenPgpEncryptResultProto(
                data = byteArrayOf(12),
                protectionMode = OpenPgpProtectionModeProto.GNUPG_OCB,
            ),
        )
        assertContentEquals(byteArrayOf(12), encryptResult.data)
        assertEquals(OpenPgpProtectionModeProto.GNUPG_OCB, encryptResult.protectionMode)
        val encryptFinal = roundTrip(
            OpenPgpEncryptFinalProto(
                data = byteArrayOf(13),
                protectionMode = OpenPgpProtectionModeProto.SEIPD_V1_MDC,
            ),
        )
        assertContentEquals(byteArrayOf(13), encryptFinal.data)
        assertEquals(OpenPgpProtectionModeProto.SEIPD_V1_MDC, encryptFinal.protectionMode)
        val decryptResult = roundTrip(
            OpenPgpDecryptResultProto(
                data = byteArrayOf(14),
                verification = verification,
                encrypted = true,
                decryptionKeyFingerprint = "A".repeat(40),
            ),
        )
        assertContentEquals(byteArrayOf(14), decryptResult.data)
        assertEquals(OpenPgpVerificationStatusProto.VALID, decryptResult.verification?.status)
        assertEquals("A".repeat(40), decryptResult.decryptionKeyFingerprint)
        val decryptFinal = roundTrip(
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(15),
                verification = verification,
                encrypted = true,
                decryptionKeyFingerprint = "B".repeat(40),
            ),
        )
        assertContentEquals(byteArrayOf(15), decryptFinal.data)
        assertEquals(OpenPgpVerificationStatusProto.VALID, decryptFinal.verification?.status)
        assertEquals("B".repeat(40), decryptFinal.decryptionKeyFingerprint)
    }

    @Test
    fun roundTripsMaximumOpenPgpExpirationAsUint32() {
        val request = OpenPgpKeyGenerateRequestProto(
            kind = OpenPgpKeyKindProto.LEGACY_ED25519_X25519,
            userId = "Alice <alice@example.invalid>",
            creationTimeEpochSeconds = 1_700_000_000L,
            expirationSeconds = UInt.MAX_VALUE,
        )

        val encoded = ProtoBuf.encodeToByteArray(request)
        val expirationWireValue = listOf(
            0x28,
            0xff,
            0xff,
            0xff,
            0xff,
            0x0f,
        ).map(Int::toByte)
        assertTrue(
            encoded.asList().windowed(expirationWireValue.size).contains(expirationWireValue),
            "OpenPGP expiration must use the five-byte uint32 protobuf representation: " +
                encoded.joinToString(separator = " ") { byte ->
                    byte.toUByte().toString(radix = 16).padStart(length = 2, padChar = '0')
                },
        )
        assertEquals(
            UInt.MAX_VALUE,
            ProtoBuf.decodeFromByteArray<OpenPgpKeyGenerateRequestProto>(encoded).expirationSeconds,
        )
    }

    @Test
    fun openPgpWriteStreamConsumesHandleAndCarriesTypedFinalPayload() {
        val finalPayload = ProtoBuf.encodeToByteArray(
            OpenPgpEncryptFinalProto(
                data = byteArrayOf(7, 8),
                protectionMode = OpenPgpProtectionModeProto.SEIPD_V1_MDC,
            ),
        )
        val bridge = FakeBridge(
            streamOpenResponse = response(UInt64ResultProto(42L)),
            streamUpdateResponse = response(BytesResultProto(byteArrayOf(5, 6))),
            streamFinishResponse = response(BytesResultProto(finalPayload)),
        )
        val session = NativeCryptoClient(bridge).openPgpEncryption(
            publicKeys = listOf(byteArrayOf(1)),
            signingPrivateKey = null,
            preferredSigningFingerprint = "",
            fileName = "payload.bin",
            armored = false,
            literalTimeEpochSeconds = 0L,
            referenceTimeEpochSeconds = 0L,
            enableCompression = true,
        )

        val updateOutput = session.update(byteArrayOf(9, 4, 3), offset = 1, length = 2)
        assertContentEquals(byteArrayOf(5, 6), updateOutput)
        assertContentEquals(byteArrayOf(0, 0), bridge.lastStreamInput)
        assertTrue(assertNotNull(bridge.lastStreamOpenRequest).all { byte -> byte == 0.toByte() })

        val decodedFinal = ProtoBuf.decodeFromByteArray<OpenPgpEncryptFinalProto>(session.finish())
        assertContentEquals(byteArrayOf(7, 8), decodedFinal.data)
        assertEquals(OpenPgpProtectionModeProto.SEIPD_V1_MDC, decodedFinal.protectionMode)
        assertEquals(1, bridge.streamFinishCalls)
        assertEquals(1, bridge.streamCloseCalls)
        assertTrue(assertNotNull(bridge.lastStreamFinishResponse).all { byte -> byte == 0.toByte() })

        val reuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, reuse.code)
        session.close()
        assertEquals(1, bridge.streamCloseCalls)
    }

    @Test
    fun preservesAbsentVersusZeroOpenPgpReferenceTime() {
        val absent = OpenPgpPublicKeyParseRequestProto(
            keyData = byteArrayOf(1),
            referenceTimeEpochSeconds = null,
        )
        val zero = absent.copy(referenceTimeEpochSeconds = 0L)

        val absentBytes = ProtoBuf.encodeToByteArray(absent)
        val zeroBytes = ProtoBuf.encodeToByteArray(zero)

        assertNotEquals(absentBytes.toList(), zeroBytes.toList())
        assertNull(
            ProtoBuf.decodeFromByteArray<OpenPgpPublicKeyParseRequestProto>(absentBytes)
                .referenceTimeEpochSeconds,
        )
        assertEquals(
            0L,
            ProtoBuf.decodeFromByteArray<OpenPgpPublicKeyParseRequestProto>(zeroBytes)
                .referenceTimeEpochSeconds,
        )
    }

    @Test
    fun usesZigZagForSignedIntResult() {
        val encoded = response(Int32ResultProto(-1))
        val resultTagIndex = encoded.indexOf(0x58)

        assertTrue(resultTagIndex >= 0)
        assertEquals(0x01, encoded[resultTagIndex + 1].toInt())
        val decoded = ProtoBuf.decodeFromByteArray<NativeResponseProto>(encoded)
        assertEquals(-1, (decoded.result as Int32ResultProto).value)
    }

    @Test
    fun encodesRandomIntBatchAtOneShotTag19() {
        val request = NativeRequestProto(
            protocolVersion = NativeCrypto.PROTOCOL_VERSION,
            operation = RandomIntsOperationProto(
                RandomIntsRequestProto(
                    bounded = true,
                    exclusiveUpperBound = 1_000,
                    count = 256,
                ),
            ),
        )

        val encoded = ProtoBuf.encodeToByteArray(request)
        val operationTagIndex = encoded.indexOf(0x9a.toByte())

        assertTrue(operationTagIndex >= 0)
        assertEquals(0x01, encoded[operationTagIndex + 1].toInt())
        val decoded = ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded)
        val batch = (decoded.operation as RandomIntsOperationProto).value
        assertTrue(batch.bounded)
        assertEquals(1_000, batch.exclusiveUpperBound)
        assertEquals(256, batch.count)
    }

    @Test
    fun encodesRepeatedAesTransformAtOneShotTag20() {
        val request = NativeRequestProto(
            protocolVersion = NativeCrypto.PROTOCOL_VERSION,
            operation = AesEcbNoPaddingTransformOperationProto(
                AesEcbNoPaddingTransformRequestProto(
                    key = ByteArray(32),
                    data = ByteArray(32),
                    rounds = 6_000,
                ),
            ),
        )

        val encoded = ProtoBuf.encodeToByteArray(request)
        val operationTagIndex = encoded.indexOf(0xa2.toByte())

        assertTrue(operationTagIndex >= 0)
        assertEquals(0x01, encoded[operationTagIndex + 1].toInt())
        val decoded = ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded)
        val transform = (decoded.operation as AesEcbNoPaddingTransformOperationProto).value
        assertEquals(6_000, transform.rounds)
        assertEquals(32, transform.data.size)
    }

    @Test
    fun roundTripsFusedAesCbcHmacOperationsAtTags45And46() {
        val operations = listOf<NativeRequestOperationProto>(
            AesCbcPkcs7HmacSha256EncryptOperationProto(
                AesCbcPkcs7HmacSha256EncryptRequestProto(
                    encryptionKey = byteArrayOf(1),
                    macKey = byteArrayOf(2),
                    iv = byteArrayOf(3),
                    plaintext = byteArrayOf(4),
                ),
            ),
            AesCbcPkcs7HmacSha256DecryptOperationProto(
                AesCbcPkcs7HmacSha256DecryptRequestProto(
                    encryptionKey = byteArrayOf(1),
                    macKey = byteArrayOf(2),
                    iv = byteArrayOf(3),
                    ciphertext = byteArrayOf(4),
                    expectedMac = byteArrayOf(5),
                ),
            ),
        )

        operations.forEachIndexed { index, operation ->
            val request = NativeRequestProto(
                protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                operation = operation,
            )
            val encoded = ProtoBuf.encodeToByteArray(request)
            assertContainsLengthDelimitedTag(encoded, fieldNumber = 45 + index)
            assertEquals(
                operation::class,
                ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded).operation::class,
            )
        }

        val result = AesCbcPkcs7HmacSha256EncryptResultProto(
            ciphertext = byteArrayOf(6, 7),
            mac = byteArrayOf(8, 9),
        )
        val decoded = roundTrip(result)
        assertContentEquals(result.ciphertext, decoded.ciphertext)
        assertContentEquals(result.mac, decoded.mac)
    }

    @Test
    fun encodesTypedStreamingOperationsAtTags11Through15() {
        val operations = listOf(
            DigestStreamOpenOperationProto(DigestStreamOpenRequestProto(HashAlgorithmProto.SHA256)),
            HmacStreamOpenOperationProto(
                HmacStreamOpenRequestProto(HashAlgorithmProto.SHA256, byteArrayOf(1)),
            ),
            AesCbcPkcs7StreamOpenOperationProto(
                AesCbcPkcs7StreamOpenRequestProto(
                    direction = CipherDirectionProto.ENCRYPT,
                    key = ByteArray(32),
                    iv = ByteArray(16),
                ),
            ),
            TwofishCbcPkcs7StreamOpenOperationProto(
                TwofishCbcPkcs7StreamOpenRequestProto(
                    direction = CipherDirectionProto.DECRYPT,
                    key = ByteArray(32),
                    iv = ByteArray(16),
                ),
            ),
            OpenPgpDetachedVerifyStreamOpenOperationProto(
                OpenPgpDetachedVerifyStreamOpenRequestProto(
                    signature = byteArrayOf(1),
                    publicKeys = listOf(byteArrayOf(2)),
                    referenceTimeEpochSeconds = 1_700_000_000L,
                ),
            ),
        )

        operations.forEachIndexed { index, operation ->
            val encoded = ProtoBuf.encodeToByteArray(
                NativeStreamOpenRequestProto(
                    protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                    operation = operation,
                ),
            )
            val expectedTag = ((11 + index) shl 3) or 2
            assertTrue(encoded.indexOf(expectedTag.toByte()) >= 0)
            val decoded = ProtoBuf.decodeFromByteArray<NativeStreamOpenRequestProto>(encoded)
            assertEquals(operation::class, decoded.operation::class)
        }
    }

    private fun digestOperation(): NativeRequestOperationProto = DigestOperationProto(
        DigestRequestProto(
            algorithm = HashAlgorithmProto.SHA256,
            data = byteArrayOf(1),
        ),
    )

    private inline fun <reified T> roundTrip(value: T): T =
        ProtoBuf.decodeFromByteArray(ProtoBuf.encodeToByteArray(value))

    private fun assertContainsLengthDelimitedTag(encoded: ByteArray, fieldNumber: Int) {
        var tag = (fieldNumber shl 3) or 2
        val expected = buildList {
            while (tag >= 0x80) {
                add(((tag and 0x7f) or 0x80).toByte())
                tag = tag ushr 7
            }
            add(tag.toByte())
        }
        assertTrue(
            encoded.asList().windowed(expected.size).any { bytes -> bytes == expected },
            "missing protobuf field $fieldNumber tag",
        )
    }

    private class FakeBridge(
        private val abiVersion: Int = NativeCrypto.EXPECTED_ABI_VERSION,
        private val capabilities: Long = allNativeCryptoCapabilitiesMask,
        private val callResponse: ByteArray = response(BytesResultProto(ByteArray(0))),
        private val callFailure: Throwable? = null,
        private val streamOpenResponse: ByteArray = response(UInt64ResultProto(1L)),
        private val streamOpenFailure: Throwable? = null,
        private val streamUpdateResponse: ByteArray = response(),
        private val streamFinishResponse: ByteArray = response(BytesResultProto(ByteArray(0))),
        private val streamCloseResponse: ByteArray = response(),
        private val streamCloseResponses: List<ByteArray>? = null,
        private val fastRandomInt: ((Int) -> Long)? = null,
        private val fastEncrypt: ((ByteArray, ByteArray) -> Long)? = null,
        private val fastDecrypt: ((ByteArray) -> Long)? = null,
    ) : NativeCryptoBridge {
        var streamUpdateCalls: Int = 0
        var streamFinishCalls: Int = 0
        var streamCloseCalls: Int = 0
        var lastStreamInput: ByteArray? = null
        var lastCallRequest: ByteArray? = null
        var lastCallResponse: ByteArray? = null
        var lastStreamOpenRequest: ByteArray? = null
        var lastStreamOpenResponse: ByteArray? = null
        var lastStreamFinishResponse: ByteArray? = null
        var lastStreamCloseResponse: ByteArray? = null

        override fun abiVersion(): Int = abiVersion

        override fun capabilities(): Long = capabilities

        override fun randomInt(exclusiveUpperBound: Int): Long = fastRandomInt
            ?.invoke(exclusiveUpperBound)
            ?: packNativeCryptoIntResult(NativeCryptoErrorCode.INTERNAL.wireValue!!, 0)

        override fun call(request: ByteArray): ByteArray {
            lastCallRequest = request
            callFailure?.let { failure -> throw failure }
            return callResponse.copyOf().also { response -> lastCallResponse = response }
        }

        override fun streamOpen(request: ByteArray): ByteArray {
            lastStreamOpenRequest = request
            streamOpenFailure?.let { failure -> throw failure }
            return streamOpenResponse.copyOf().also { response -> lastStreamOpenResponse = response }
        }

        override fun streamUpdate(handle: Long, input: ByteArray): ByteArray {
            streamUpdateCalls += 1
            lastStreamInput = input
            return streamUpdateResponse.copyOf()
        }

        override fun streamFinish(handle: Long): ByteArray {
            streamFinishCalls += 1
            return streamFinishResponse.copyOf().also { response ->
                lastStreamFinishResponse = response
            }
        }

        override fun streamClose(handle: Long): ByteArray {
            streamCloseCalls += 1
            val configured = streamCloseResponses
                ?.getOrNull(streamCloseCalls - 1)
                ?: streamCloseResponse
            return configured.copyOf().also { response ->
                lastStreamCloseResponse = response
            }
        }

        override fun aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey: ByteArray,
            macKey: ByteArray,
            iv: ByteArray,
            plaintext: ByteArray,
            ciphertextOutput: ByteArray,
            macOutput: ByteArray,
        ): Long {
            fastEncrypt?.let { operation -> return operation(ciphertextOutput, macOutput) }
            ciphertextOutput.fill(0)
            macOutput.fill(0)
            return packNativeCryptoFastResult(NativeCryptoErrorCode.INTERNAL.wireValue!!, 0)
        }

        override fun aesCbcPkcs7HmacSha256Decrypt(
            encryptionKey: ByteArray,
            macKey: ByteArray,
            iv: ByteArray,
            ciphertext: ByteArray,
            expectedMac: ByteArray,
            plaintextOutput: ByteArray,
        ): Long {
            fastDecrypt?.let { operation -> return operation(plaintextOutput) }
            plaintextOutput.fill(0)
            return packNativeCryptoFastResult(NativeCryptoErrorCode.INTERNAL.wireValue!!, 0)
        }
    }
}

private fun response(
    result: NativeResponseResultProto? = null,
    code: NativeErrorCodeProto = NativeErrorCodeProto.OK,
    operation: String = "test",
): ByteArray = ProtoBuf.encodeToByteArray(
    NativeResponseProto(
        protocolVersion = NativeCrypto.PROTOCOL_VERSION,
        status = NativeStatusProto(code = code, operation = operation),
        result = result,
    ),
)
