@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto.benchmark

import com.artemchep.keyguard.nativecrypto.BytesResultProto
import com.artemchep.keyguard.nativecrypto.DigestOperationProto
import com.artemchep.keyguard.nativecrypto.DigestRequestProto
import com.artemchep.keyguard.nativecrypto.HashAlgorithmProto
import com.artemchep.keyguard.nativecrypto.HmacOperationProto
import com.artemchep.keyguard.nativecrypto.HmacRequestProto
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPlatform
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeErrorCodeProto
import com.artemchep.keyguard.nativecrypto.NativeRequestProto
import com.artemchep.keyguard.nativecrypto.NativeResponseProto
import com.artemchep.keyguard.nativecrypto.NativeStatusProto
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in breakdown of the fixed and payload-dependent costs around the Native Crypto boundary.
 *
 * Run with `./gradlew :util:crypto:nativeCryptoLayerBenchmark`. This test is excluded from the
 * normal desktop suite because it deliberately repeats native calls many thousands of times.
 */
class NativeCryptoLayerBenchmarkTest {
    private val harness = NativeCryptoLayerBenchmarkHarness()

    @Test
    fun `measure Native Crypto boundary layers`() {
        NativeCrypto.ensureReady()
        val key = deterministicBytes(KEY_BYTES, seed = 0x31)
        val responseEnvelope = successfulBytesResponse(deterministicBytes(HMAC_BYTES, seed = 0x52))

        println(
            "$OUTPUT_PREFIX kind=environment" +
                " os=${token(System.getProperty("os.name"))}" +
                " arch=${token(System.getProperty("os.arch"))}" +
                " jvm=${token(System.getProperty("java.version"))}" +
                " native_abi=${NativeCrypto.abiVersion}" +
                " payloads=${PAYLOAD_SIZES.joinToString(",")}" +
                " hmac_payloads=${HMAC_PAYLOAD_SIZES.joinToString(",")}",
        )

        val runs = buildList {
            addRawJniTransitionBenchmark()
            addRequestEncodingBenchmarks(key)
            addResponseDecodingBenchmark(responseEnvelope)
            addGenericCallBenchmark()
            addHmacBenchmarks(key)
            addBitwardenCompositionBenchmarks()
        }

        assertTrue(runs.isNotEmpty())
        assertTrue(runs.all { run -> run.samplesNsPerOperation.isNotEmpty() })
        assertTrue(runs.all { run -> run.medianNsPerOperation > 0.0 })
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addRawJniTransitionBenchmark() {
        assertEquals(NativeCrypto.EXPECTED_ABI_VERSION, NativeCryptoPlatform.abiVersion())
        add(
            harness.measure(
                spec =
                    NativeCryptoLayerBenchmarkSpec(
                        name = "raw-jni-abi-version",
                        layer = "desktop_loader_wrapper_plus_jni_transition",
                        operationsPerSample = 20_000,
                    ),
            ) {
                NativeCryptoPlatform.abiVersion().toLong()
            },
        )
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addRequestEncodingBenchmarks(
        key: ByteArray,
    ) {
        PAYLOAD_SIZES.forEach { payloadBytes ->
            val data = deterministicBytes(payloadBytes, seed = payloadBytes xor 0x43)
            val request =
                NativeRequestProto(
                    protocolVersion = NativeCrypto.PROTOCOL_VERSION,
                    operation =
                        HmacOperationProto(
                            HmacRequestProto(
                                algorithm = HashAlgorithmProto.SHA256,
                                key = key,
                                data = data,
                            ),
                        ),
                )
            val encoded = ProtoBuf.encodeToByteArray(request)
            val decoded = ProtoBuf.decodeFromByteArray<NativeRequestProto>(encoded)
            val decodedHmac = decoded.operation as HmacOperationProto
            assertEquals(NativeCrypto.PROTOCOL_VERSION, decoded.protocolVersion)
            assertEquals(HashAlgorithmProto.SHA256, decodedHmac.value.algorithm)
            assertContentEquals(key, decodedHmac.value.key)
            assertContentEquals(data, decodedHmac.value.data)

            add(
                harness.measure(
                    spec =
                        NativeCryptoLayerBenchmarkSpec(
                            name = "protobuf-request-encode-${sizeLabel(payloadBytes)}",
                            layer = "kotlin_protobuf_request_encode",
                            payloadBytes = payloadBytes,
                            operationsPerSample = operationsPerSample(payloadBytes),
                        ),
                ) {
                    benchmarkChecksum(ProtoBuf.encodeToByteArray(request))
                },
            )
        }
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addResponseDecodingBenchmark(
        responseEnvelope: ByteArray,
    ) {
        val decoded = ProtoBuf.decodeFromByteArray<NativeResponseProto>(responseEnvelope)
        assertEquals(NativeErrorCodeProto.OK, decoded.status?.code)
        assertEquals(HMAC_BYTES, (decoded.result as BytesResultProto).value.size)

        add(
            harness.measure(
                spec =
                    NativeCryptoLayerBenchmarkSpec(
                        name = "protobuf-response-decode-hmac32",
                        layer = "kotlin_protobuf_response_decode",
                        payloadBytes = responseEnvelope.size,
                        operationsPerSample = 20_000,
                    ),
            ) {
                val response = ProtoBuf.decodeFromByteArray<NativeResponseProto>(responseEnvelope)
                benchmarkChecksum((response.result as BytesResultProto).value)
            },
        )
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addGenericCallBenchmark() {
        val expected = NativeCryptoPrimitives.sha256(EMPTY_BYTES)
        val operation =
            DigestOperationProto(
                DigestRequestProto(
                    algorithm = HashAlgorithmProto.SHA256,
                    data = EMPTY_BYTES,
                ),
            )
        val actual =
            NativeCrypto.call(
                operationName = "digest",
                operation = operation,
            ) as BytesResultProto
        assertContentEquals(expected, actual.value)

        add(
            harness.measure(
                spec =
                    NativeCryptoLayerBenchmarkSpec(
                        name = "generic-call-sha256-empty",
                        layer = "protobuf_jni_rust_round_trip",
                        payloadBytes = 0,
                        operationsPerSample = 5_000,
                    ),
            ) {
                val result =
                    NativeCrypto.call(
                        operationName = "digest",
                        operation = operation,
                    ) as BytesResultProto
                benchmarkChecksum(result.value)
            },
        )
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addHmacBenchmarks(key: ByteArray) {
        HMAC_PAYLOAD_SIZES.forEach { payloadBytes ->
            val data = deterministicBytes(payloadBytes, seed = payloadBytes xor 0x64)
            val oneShot = hmacOneShot(key, data)
            val streaming = hmacStreaming(key, data)
            assertContentEquals(oneShot, streaming)

            add(
                harness.measure(
                    spec =
                        NativeCryptoLayerBenchmarkSpec(
                            name = "hmac-sha256-one-shot-${sizeLabel(payloadBytes)}",
                            layer = "one_shot_hmac",
                            payloadBytes = payloadBytes,
                            operationsPerSample = hmacOperationsPerSample(payloadBytes),
                        ),
                ) {
                    benchmarkChecksum(hmacOneShot(key, data))
                },
            )
            add(
                harness.measure(
                    spec =
                        NativeCryptoLayerBenchmarkSpec(
                            name = "hmac-sha256-stream-${sizeLabel(payloadBytes)}",
                            layer = "stream_open_update_finish_close",
                            payloadBytes = payloadBytes,
                            operationsPerSample = hmacOperationsPerSample(payloadBytes),
                        ),
                ) {
                    benchmarkChecksum(hmacStreaming(key, data))
                },
            )
        }
    }

    private fun MutableList<NativeCryptoLayerBenchmarkRun>.addBitwardenCompositionBenchmarks() {
        val encryptionKey = deterministicBytes(KEY_BYTES, seed = 0x21)
        val macKey = deterministicBytes(KEY_BYTES, seed = 0x42)
        val iv = deterministicBytes(IV_BYTES, seed = 0x63)

        PAYLOAD_SIZES.forEach { payloadBytes ->
            val plaintext = deterministicBytes(payloadBytes, seed = payloadBytes xor 0x75)
            val baseline = composedEncrypt(encryptionKey, macKey, iv, plaintext)
            val protobuf = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256EncryptViaProtobuf(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
            )
            val fast = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
                plaintext = plaintext,
            )
            assertContentEquals(baseline.ciphertext, protobuf.ciphertext)
            assertContentEquals(baseline.mac, protobuf.mac)
            assertContentEquals(baseline.ciphertext, fast.ciphertext)
            assertContentEquals(baseline.mac, fast.mac)
            assertContentEquals(
                plaintext,
                NativeCryptoPrimitives.aesCbcPkcs7HmacSha256DecryptViaProtobuf(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = baseline.ciphertext,
                    expectedMac = baseline.mac,
                ),
            )
            assertContentEquals(
                plaintext,
                NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = baseline.ciphertext,
                    expectedMac = baseline.mac,
                ),
            )

            val operations = operationsPerSample(payloadBytes)
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-encrypt-composed-${sizeLabel(payloadBytes)}",
                        layer =
                            "aes_generic_plus_hmac_stream_" +
                                "${composedNativeCrossings(baseline.ciphertext.size)}_crossings",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    composedEncrypt(encryptionKey, macKey, iv, plaintext).checksum()
                },
            )
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-encrypt-fused-protobuf-${sizeLabel(payloadBytes)}",
                        layer = "fused_protobuf_single_crossing",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    NativeCryptoPrimitives.aesCbcPkcs7HmacSha256EncryptViaProtobuf(
                        encryptionKey = encryptionKey,
                        macKey = macKey,
                        iv = iv,
                        plaintext = plaintext,
                    ).checksum()
                },
            )
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-encrypt-fast-${sizeLabel(payloadBytes)}",
                        layer = "fused_fixed_shape_single_crossing",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
                        encryptionKey = encryptionKey,
                        macKey = macKey,
                        iv = iv,
                        plaintext = plaintext,
                    ).checksum()
                },
            )
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-decrypt-composed-${sizeLabel(payloadBytes)}",
                        layer =
                            "hmac_stream_plus_aes_generic_" +
                                "${composedNativeCrossings(baseline.ciphertext.size)}_crossings",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    benchmarkChecksum(
                        composedDecrypt(
                            encryptionKey,
                            macKey,
                            iv,
                            baseline.ciphertext,
                            baseline.mac,
                        ),
                    )
                },
            )
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-decrypt-fused-protobuf-${sizeLabel(payloadBytes)}",
                        layer = "fused_protobuf_single_crossing",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    benchmarkChecksum(
                        NativeCryptoPrimitives.aesCbcPkcs7HmacSha256DecryptViaProtobuf(
                            encryptionKey = encryptionKey,
                            macKey = macKey,
                            iv = iv,
                            ciphertext = baseline.ciphertext,
                            expectedMac = baseline.mac,
                        ),
                    )
                },
            )
            add(
                harness.measure(
                    NativeCryptoLayerBenchmarkSpec(
                        name = "bitwarden-decrypt-fast-${sizeLabel(payloadBytes)}",
                        layer = "fused_fixed_shape_single_crossing",
                        payloadBytes = payloadBytes,
                        operationsPerSample = operations,
                    ),
                ) {
                    benchmarkChecksum(
                        NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
                            encryptionKey = encryptionKey,
                            macKey = macKey,
                            iv = iv,
                            ciphertext = baseline.ciphertext,
                            expectedMac = baseline.mac,
                        ),
                    )
                },
            )
        }
    }

    private fun composedEncrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
    ): BenchmarkCipher = NativeCryptoPrimitives.aesCbcPkcs7Encrypt(
        key = encryptionKey,
        iv = iv,
        data = plaintext,
    ).let { ciphertext ->
        BenchmarkCipher(
            ciphertext = ciphertext,
            mac = hmacStreaming(macKey, iv, ciphertext),
        )
    }

    private fun composedDecrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
    ): ByteArray {
        val actualMac = hmacStreaming(macKey, iv, ciphertext)
        return try {
            check(MessageDigest.isEqual(actualMac, expectedMac))
            NativeCryptoPrimitives.aesCbcPkcs7Decrypt(
                key = encryptionKey,
                iv = iv,
                data = ciphertext,
            )
        } finally {
            actualMac.fill(0)
        }
    }

    private fun hmacOneShot(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val result = NativeCrypto.call(
            operationName = "hmac",
            operation = HmacOperationProto(
                HmacRequestProto(
                    algorithm = HashAlgorithmProto.SHA256,
                    key = key,
                    data = data,
                ),
            ),
        ) as BytesResultProto
        return result.value
    }

    private fun hmacStreaming(
        key: ByteArray,
        vararg chunks: ByteArray,
    ): ByteArray =
        NativeCryptoPrimitives.createHmacSha256(key).use { session ->
            chunks.forEach { data ->
                var offset = 0
                while (offset < data.size) {
                    val length = minOf(BENCHMARK_STREAM_CHUNK_BYTES, data.size - offset)
                    checkHmacUpdateOutput(session.update(data, offset, length))
                    offset += length
                }
            }
            session.finish()
        }

    private fun checkHmacUpdateOutput(output: ByteArray) {
        check(output.isEmpty()) { "Native HMAC update produced output" }
    }

    private companion object {
        const val OUTPUT_PREFIX = "[native-crypto-layer-benchmark]"
        const val KEY_BYTES = 32
        const val IV_BYTES = 16
        const val HMAC_BYTES = 32
        val EMPTY_BYTES = ByteArray(0)
        val PAYLOAD_SIZES = listOf(0, 32, 1024, 64 * 1024)
        val HMAC_PAYLOAD_SIZES = listOf(
            0,
            32,
            1024,
            2 * 1024,
            4 * 1024,
            8 * 1024,
            16 * 1024,
            32 * 1024,
            64 * 1024,
        )
    }
}

private data class BenchmarkCipher(
    val ciphertext: ByteArray,
    val mac: ByteArray,
)

private fun BenchmarkCipher.checksum(): Long =
    benchmarkChecksum(ciphertext) * 31L + benchmarkChecksum(mac)

private fun com.artemchep.keyguard.nativecrypto.NativeAesCbcHmacSha256Result.checksum(): Long =
    benchmarkChecksum(ciphertext) * 31L + benchmarkChecksum(mac)

private fun successfulBytesResponse(value: ByteArray): ByteArray =
    ProtoBuf.encodeToByteArray(
        NativeResponseProto(
            protocolVersion = NativeCrypto.PROTOCOL_VERSION,
            status =
                NativeStatusProto(
                    code = NativeErrorCodeProto.OK,
                    operation = "hmac",
                ),
            result = BytesResultProto(value),
        ),
    )

private fun deterministicBytes(
    size: Int,
    seed: Int,
): ByteArray = ByteArray(size) { index -> ((seed + index * 37) and 0xff).toByte() }

private fun operationsPerSample(payloadBytes: Int): Int =
    when {
        payloadBytes <= 32 -> 5_000
        payloadBytes <= 1024 -> 2_000
        else -> 50
    }

private fun hmacOperationsPerSample(payloadBytes: Int): Int =
    when {
        payloadBytes <= 32 -> 5_000
        payloadBytes <= 4 * 1024 -> 2_000
        payloadBytes <= 16 * 1024 -> 1_000
        payloadBytes <= 32 * 1024 -> 500
        else -> 50
    }

private fun composedNativeCrossings(ciphertextBytes: Int): Int {
    val ciphertextUpdates =
        (ciphertextBytes.toLong() + BENCHMARK_STREAM_CHUNK_BYTES - 1L) /
            BENCHMARK_STREAM_CHUNK_BYTES
    return 5 + ciphertextUpdates.toInt()
}

private const val BENCHMARK_STREAM_CHUNK_BYTES: Int = 64 * 1024

private fun sizeLabel(size: Int): String =
    when {
        size == 0 -> "0b"
        size % (1024 * 1024) == 0 -> "${size / (1024 * 1024)}mib"
        size % 1024 == 0 -> "${size / 1024}kib"
        else -> "${size}b"
    }

private fun token(value: String?): String =
    value
        ?.trim()
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        ?.takeIf(String::isNotEmpty)
        ?: "unknown"
