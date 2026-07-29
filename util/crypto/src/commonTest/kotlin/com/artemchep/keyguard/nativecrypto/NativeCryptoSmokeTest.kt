package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCryptoSmokeTest {
    @Test
    fun loadsNativeLibraryAndExecutesSha256() {
        NativeCrypto.ensureReady()

        assertEquals(NativeCrypto.EXPECTED_ABI_VERSION, NativeCrypto.abiVersion)
        assertTrue(NativeCrypto.capabilities.containsAll(NativeCryptoCapability.entries))
        assertContentEquals(
            expected = hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            actual = NativeCrypto.primitives.sha256("abc".encodeToByteArray()),
        )
        // A handful of draws: in range, and actually varying — a stub answering a
        // constant (0, say) satisfies the bound but is not a random source.
        val draws = List(16) { NativeCrypto.primitives.randomInt(1_000) }
        assertTrue(draws.all { it in 0 until 1_000 }, "out of range draw in $draws")
        assertTrue(draws.toSet().size > 1, "randomInt answered a constant: $draws")
    }

    @Test
    fun streamsPayloadLargerThanControlEnvelope() {
        val data = ByteArray(NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES + 17) { index ->
            (index * 31 + 7).toByte()
        }

        assertContentEquals(
            expected = hex("c3efec16147baac59a8e248f01ddd0f247fc5d1e3b4c634bdd2e984ef79e0835"),
            actual = NativeCrypto.primitives.sha256(data),
        )
        assertContentEquals(
            expected = hex(
                "0f3181f8c6e2a6a662f94f14e56c68f422fe5d393f10e87db9f1d7a6889d625" +
                    "bdc2cdc06b46ebae0c24c5947782e6d4e9a707527e71eb79289cce030f2d159fa",
            ),
            actual = NativeCrypto.primitives.hmac(
                key = "stream-key".encodeToByteArray(),
                data = data,
                algorithm = NativeHashAlgorithm.SHA_512,
            ),
        )

        val key = ByteArray(32) { it.toByte() }
        val macKey = ByteArray(32) { (it + 64).toByte() }
        val iv = ByteArray(16) { (it + 32).toByte() }
        val encrypted = NativeCrypto.primitives.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = key,
            macKey = macKey,
            iv = iv,
            plaintext = data,
        )
        val decrypted = NativeCrypto.primitives.aesCbcPkcs7HmacSha256Decrypt(
            encryptionKey = key,
            macKey = macKey,
            iv = iv,
            ciphertext = encrypted.ciphertext,
            expectedMac = encrypted.mac,
        )
        try {
            assertContentEquals(data, decrypted)
        } finally {
            encrypted.ciphertext.fill(0)
            encrypted.mac.fill(0)
            decrypted.fill(0)
            data.fill(0)
        }
    }

    @Test
    fun fusedBitwardenFastAndProtobufPathsMatchGoldenAndAuthenticateBeforeDecrypt() {
        val encryptionKey: ByteArray = (0 until 32).map(Int::toByte).toByteArray()
        val macKey: ByteArray = (32 until 64).map(Int::toByte).toByteArray()
        val iv: ByteArray = (64 until 80).map(Int::toByte).toByteArray()
        val plaintext = "Bitwarden fused AES-CBC/HMAC test vector".encodeToByteArray()
        val expectedCiphertext = hex(
            "b3ae5dcb9dd806f8266f89d2e9e3489d37964364df9a2b1767d16f3fda8f82ae" +
                "088a3c1a342b9b5b72417ed002bc0248",
        )
        val expectedMac = hex(
            "6f9cc3bd0c5cd61850923fe87d0edb133fc1f84e7f7a513658b87dd2d35359c8",
        )

        val fast = NativeCrypto.primitives.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            plaintext = plaintext,
        )
        val protobuf = NativeCrypto.primitives.aesCbcPkcs7HmacSha256EncryptViaProtobuf(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            plaintext = plaintext,
        )
        try {
            assertContentEquals(expectedCiphertext, fast.ciphertext)
            assertContentEquals(expectedMac, fast.mac)
            assertContentEquals(fast.ciphertext, protobuf.ciphertext)
            assertContentEquals(fast.mac, protobuf.mac)
            assertContentEquals(
                plaintext,
                NativeCrypto.primitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = fast.ciphertext,
                    expectedMac = fast.mac,
                ),
            )

            val malformedCiphertext = ByteArray(15) { 0x44.toByte() }
            val unauthenticated = assertFailsWith<NativeCryptoException> {
                NativeCrypto.primitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = malformedCiphertext,
                    expectedMac = ByteArray(32) { 0x55.toByte() },
                )
            }
            assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, unauthenticated.code)

            val emptyUnauthenticated = assertFailsWith<NativeCryptoException> {
                NativeCrypto.primitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    ciphertext = ByteArray(0),
                    expectedMac = ByteArray(0),
                )
            }
            assertEquals(
                NativeCryptoErrorCode.AUTHENTICATION_FAILED,
                emptyUnauthenticated.code,
            )

            val authenticatedMalformedMac = NativeCrypto.primitives.hmac(
                key = macKey,
                data = iv + malformedCiphertext,
                algorithm = NativeHashAlgorithm.SHA_256,
            )
            try {
                val malformed = assertFailsWith<NativeCryptoException> {
                    NativeCrypto.primitives.aesCbcPkcs7HmacSha256Decrypt(
                        encryptionKey = encryptionKey,
                        macKey = macKey,
                        iv = iv,
                        ciphertext = malformedCiphertext,
                        expectedMac = authenticatedMalformedMac,
                    )
                }
                assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, malformed.code)
            } finally {
                authenticatedMalformedMac.fill(0)
            }
        } finally {
            fast.ciphertext.fill(0)
            fast.mac.fill(0)
            protobuf.ciphertext.fill(0)
            protobuf.mac.fill(0)
        }
    }

    @Test
    fun fusedBitwardenStreamsMatchOneShotAcross64KiBChunksAndRejectTampering() {
        val encryptionKey = ByteArray(32) { index -> index.toByte() }
        val macKey = ByteArray(32) { index -> (index + 32).toByte() }
        val iv = ByteArray(16) { index -> (index + 64).toByte() }
        val plaintext = ByteArray(100_003) { index -> (index * 31 + 7).toByte() }
        val oneShot = NativeCrypto.primitives.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            plaintext = plaintext,
        )
        val streamedCiphertext = ArrayList<ByteArray>()
        var streamedFinal: NativeAesCbcHmacSha256Result? = null
        try {
            NativeCrypto.primitives.createAesCbcPkcs7HmacSha256Encryptor(
                encryptionKey = encryptionKey,
                macKey = macKey,
                iv = iv,
            ).use { encryptor ->
                streamedCiphertext += encryptor.update(plaintext, 0, 64 * 1024)
                streamedCiphertext += encryptor.update(
                    plaintext,
                    64 * 1024,
                    plaintext.size - 64 * 1024,
                )
                streamedFinal = encryptor.finish()
            }
            val final = checkNotNull(streamedFinal)
            val ciphertext = streamedCiphertext.fold(ByteArray(0)) { accumulated, chunk ->
                accumulated + chunk
            } + final.ciphertext
            try {
                assertContentEquals(oneShot.ciphertext, ciphertext)
                assertContentEquals(oneShot.mac, final.mac)

                val decryptedChunks = ArrayList<ByteArray>()
                NativeCrypto.primitives.createAesCbcPkcs7HmacSha256Decryptor(
                    encryptionKey = encryptionKey,
                    macKey = macKey,
                    iv = iv,
                    expectedMac = final.mac,
                ).use { decryptor ->
                    var offset = 0
                    while (offset < ciphertext.size) {
                        val length = minOf(64 * 1024, ciphertext.size - offset)
                        decryptedChunks += decryptor.updateProvisional(ciphertext, offset, length)
                        offset += length
                    }
                    decryptedChunks += decryptor.authenticateAndFinish()
                }
                val decrypted = decryptedChunks.fold(ByteArray(0)) { accumulated, chunk ->
                    accumulated + chunk
                }
                try {
                    assertContentEquals(plaintext, decrypted)
                } finally {
                    decrypted.fill(0)
                    decryptedChunks.forEach { it.fill(0) }
                }

                val tamperedMac = final.mac.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                try {
                    var provisionalByteCount = 0
                    val failure = assertFailsWith<NativeCryptoException> {
                        NativeCrypto.primitives.createAesCbcPkcs7HmacSha256Decryptor(
                            encryptionKey = encryptionKey,
                            macKey = macKey,
                            iv = iv,
                            expectedMac = tamperedMac,
                        ).use { decryptor ->
                            var offset = 0
                            while (offset < ciphertext.size) {
                                val length = minOf(64 * 1024, ciphertext.size - offset)
                                val provisionalPlaintext = decryptor.updateProvisional(
                                    ciphertext,
                                    offset,
                                    length,
                                )
                                provisionalByteCount += provisionalPlaintext.size
                                provisionalPlaintext.fill(0)
                                offset += length
                            }
                            decryptor.authenticateAndFinish().fill(0)
                        }
                    }
                    assertTrue(provisionalByteCount > 0)
                    assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
                } finally {
                    tamperedMac.fill(0)
                }
            } finally {
                ciphertext.fill(0)
            }
        } finally {
            oneShot.ciphertext.fill(0)
            oneShot.mac.fill(0)
            streamedCiphertext.forEach { it.fill(0) }
            streamedFinal?.ciphertext?.fill(0)
            streamedFinal?.mac?.fill(0)
            plaintext.fill(0)
        }
    }

    @Test
    fun appliesRepeatedAesWithoutPerRoundNativeCalls() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(16) { it.toByte() }

        assertContentEquals(
            expected = hex("74d0587207a5aeaa5f0aeedf81ca18bf"),
            actual = NativeCrypto.primitives.aesEcbNoPaddingTransform(
                key = key,
                data = data,
                rounds = 7,
            ),
        )
        assertContentEquals(
            expected = byteArrayOf(1, 2, 3),
            actual = NativeCrypto.primitives.aesEcbNoPaddingTransform(
                key = ByteArray(0),
                data = byteArrayOf(1, 2, 3),
                rounds = 0,
            ),
        )
    }

    @Test
    fun protectsSshAgentTcpFramesWithTheFixedVector() {
        val key = ByteArray(32) { index -> index.toByte() }
        val nonce = hex("a0a1a2a30000000000000001")
        val header = hex("4b5341470203000000000000000100000028")
        val plaintext = "keyguard-ssh-agent-frame".encodeToByteArray()
        val expectedCiphertext = hex(
            "4cb94ca92fd4281424e0b87c31a8a7cbabb723966ade916ef50ed0595bcf22b4" +
                "b63cd9fd80bc498b",
        )

        val ciphertext = NativeCrypto.primitives.sshAgentTcpChaCha20Poly1305Encrypt(
            key = key,
            nonce = nonce,
            header = header,
            payload = plaintext,
        )
        assertContentEquals(expectedCiphertext, ciphertext)
        assertContentEquals(
            expected = plaintext,
            actual = NativeCrypto.primitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = ciphertext,
            ),
        )

        val tampered = ciphertext.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        val error = assertFailsWith<NativeCryptoException> {
            NativeCrypto.primitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = tampered,
            )
        }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, error.code)
    }

    @Test
    fun generatesParsesFormatsAndSignsSshKeysThroughTheRealNativeLibrary() {
        val message = "native-crypto-primitives-smoke".encodeToByteArray()
        val ed25519 = NativeCrypto.ssh.generate(NativeSshKeyType.ED25519)
        var ed25519Signature: ByteArray? = null
        try {
            val description = NativeCrypto.ssh.describe(
                type = ed25519.type,
                privateKey = ed25519.privateKey,
                publicKey = ed25519.publicKey,
            )
            assertTrue(description.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
            assertTrue(description.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
            assertTrue(description.privateFingerprint.startsWith("SHA256:"))
            assertTrue(description.publicFingerprint.startsWith("SHA256:"))
            val parsed = NativeCrypto.ssh.parse(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
            )
            try {
                assertEquals(NativeSshKeyType.ED25519, parsed.type)
                assertContentEquals(ed25519.privateKey, parsed.privateKey)
                assertContentEquals(ed25519.publicKey, parsed.publicKey)
            } finally {
                parsed.privateKey.fill(0)
            }
            val signed = NativeCrypto.ssh.sign(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
                data = message,
                flags = 0,
            )
            ed25519Signature = signed.signature
            assertEquals("ssh-ed25519", signed.algorithm)
            assertEquals(64, signed.signature.size)
        } finally {
            ed25519.privateKey.fill(0)
            ed25519Signature?.fill(0)
        }

        val rsa = NativeCrypto.ssh.generate(NativeSshKeyType.RSA, rsaBits = 1024)
        var rsaSignature: ByteArray? = null
        try {
            assertEquals(1024, NativeCrypto.ssh.privateKeyRsaBits(rsa.privateKey))
            val privateKeyPem = NativeCrypto.ssh.formatPrivateKey(rsa.type, rsa.privateKey)
            val publicKeyOpenSsh = NativeCrypto.ssh.describe(
                type = rsa.type,
                privateKey = rsa.privateKey,
                publicKey = rsa.publicKey,
            ).publicKeyOpenSsh
            val signed = NativeCrypto.ssh.sign(
                privateKeyPem = privateKeyPem,
                publicKeyOpenSsh = publicKeyOpenSsh,
                data = message,
                flags = 0x06,
            )
            rsaSignature = signed.signature
            assertEquals("rsa-sha2-512", signed.algorithm)
            assertEquals(128, signed.signature.size)
        } finally {
            rsa.privateKey.fill(0)
            rsaSignature?.fill(0)
            message.fill(0)
        }
    }

    @Test
    fun exportsValidatedEd25519AndRsaPairsForCxfThroughTheRealNativeLibrary() {
        listOf(
            NativeSshKeyType.ED25519 to null,
            NativeSshKeyType.RSA to 1024,
        ).forEach { (type, rsaBits) ->
            val material = NativeCrypto.ssh.generate(type, rsaBits)
            var exported: NativeSshKeyCxfExport? = null
            try {
                val description = NativeCrypto.ssh.describe(
                    type = material.type,
                    privateKey = material.privateKey,
                    publicKey = material.publicKey,
                )
                val result = NativeCrypto.ssh.exportCxf(
                    privateKeyPem = description.privateKeyPem,
                    publicKeyOpenSsh = description.publicKeyOpenSsh,
                )
                exported = result

                assertEquals(type, result.type)
                assertTrue(result.privateKeyPkcs8.isNotEmpty())
                assertEquals(0x30.toByte(), result.privateKeyPkcs8.first())
            } finally {
                material.privateKey.fill(0)
                exported?.privateKeyPkcs8?.fill(0)
            }
        }
    }

    @Test
    fun exportsEd25519OpenSshToTheIndependentPkcs8GoldenVector() {
        // Ed25519 OpenSSH key (`ssh-keygen -t ed25519`). The expected DER is an
        // RFC 8410 PKCS#8 v1 template assembled independently from the key's
        // 32-byte seed.
        val goldenPem = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW",
            "QyNTUxOQAAACCRW3vhbnH4ErsDEybqMu75IyghrTkyzDa30aKoSWgnkgAAAJBlR0JRZUdC",
            "UQAAAAtzc2gtZWQyNTUxOQAAACCRW3vhbnH4ErsDEybqMu75IyghrTkyzDa30aKoSWgnkg",
            "AAAEBJ9Y0pa8/Bvf2KAtsI7ulbNYoG6KAFTolkWkCCiMFaFJFbe+FucfgSuwMTJuoy7vkj",
            "KCGtOTLMNrfRoqhJaCeSAAAADWtleWd1YXJkLXRlc3Q=",
            "-----END OPENSSH PRIVATE KEY-----",
        ).joinToString("\n", postfix = "\n")
        val goldenPublic =
            "ssh-ed25519 " +
                "AAAAC3NzaC1lZDI1NTE5AAAAIJFbe+FucfgSuwMTJuoy7vkjKCGtOTLMNrfRoqhJaCeS"

        val exported = NativeCrypto.ssh.exportCxf(
            privateKeyPem = goldenPem,
            publicKeyOpenSsh = goldenPublic,
        )
        try {
            assertEquals(NativeSshKeyType.ED25519, exported.type)
            assertContentEquals(
                expected = hex(
                    "302e020100300506032b65700422042049f58d296bcfc1bdfd8a02db08eee95b" +
                        "358a06e8a0054e89645a408288c15a14",
                ),
                actual = exported.privateKeyPkcs8,
            )
        } finally {
            exported.privateKeyPkcs8.fill(0)
        }
    }

    @Test
    fun exportCxfRejectsAnEncryptedOpenSshKey() {
        // An unconvertible key must surface as a NativeCryptoException, which is
        // what NativeSshKeyPkcs8Exporter folds into a single skipped credential.
        val encryptedPem = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDdgMlS4S",
            "not-real-key-material",
            "-----END OPENSSH PRIVATE KEY-----",
        ).joinToString("\n", postfix = "\n")

        val error = assertFailsWith<NativeCryptoException> {
            NativeCrypto.ssh.exportCxf(
                privateKeyPem = encryptedPem,
                publicKeyOpenSsh =
                    "ssh-ed25519 " +
                        "AAAAC3NzaC1lZDI1NTE5AAAAIJFbe+FucfgSuwMTJuoy7vkjKCGtOTLMNrfRoqhJaCeS",
            )
        }
        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, error.code)
    }

    private fun hex(value: String): ByteArray = value
        .chunked(2)
        .map { byte -> byte.toInt(16).toByte() }
        .toByteArray()
}
