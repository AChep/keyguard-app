package com.artemchep.keyguard.nativecrypto

/** Result of executing native crypto through a packaged Desktop application image. */
public data class NativeCryptoDesktopSmokeResult(
    public val abiVersion: Int,
    public val capabilities: Set<NativeCryptoCapability>,
)

/**
 * Verifies the library bundled in the final Desktop application resources.
 *
 * This intentionally rejects the development-only library-path override so a passing smoke test
 * proves that [NativeCryptoLibraryLoader] resolved the packaged application resource itself.
 */
public object NativeCryptoDesktopSmoke {
    public fun runPackaged(): NativeCryptoDesktopSmokeResult {
        requirePackagedLibrary()
        loadPackagedLibrary()

        NativeCrypto.ensureReady()
        val abiVersion = NativeCrypto.abiVersion
        if (abiVersion != NativeCrypto.EXPECTED_ABI_VERSION) {
            throw NativeCryptoException(
                operation = "packaged_smoke.abi",
                code = NativeCryptoErrorCode.ABI_MISMATCH,
            )
        }

        val capabilities = NativeCrypto.capabilities
        if (!capabilities.containsAll(NativeCryptoCapability.entries)) {
            throw NativeCryptoException(
                operation = "packaged_smoke.capabilities",
                code = NativeCryptoErrorCode.MISSING_CAPABILITY,
            )
        }

        val actualDigest = NativeCrypto.primitives.sha256("abc".encodeToByteArray())
        try {
            if (!actualDigest.contentEquals(EXPECTED_SHA256_ABC)) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.sha256",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            actualDigest.fill(0)
        }

        val sshAgentCiphertext = NativeCrypto.primitives.sshAgentTcpChaCha20Poly1305Encrypt(
            key = ByteArray(32) { index -> index.toByte() },
            nonce = SSH_AGENT_NONCE,
            header = SSH_AGENT_HEADER,
            payload = SSH_AGENT_PLAINTEXT,
        )
        var sshAgentPlaintext: ByteArray? = null
        try {
            if (!sshAgentCiphertext.contentEquals(EXPECTED_SSH_AGENT_CIPHERTEXT)) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.ssh_agent_tcp_chacha20_poly1305",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
            sshAgentPlaintext = NativeCrypto.primitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = ByteArray(32) { index -> index.toByte() },
                nonce = SSH_AGENT_NONCE,
                header = SSH_AGENT_HEADER,
                payload = sshAgentCiphertext,
            )
            if (!sshAgentPlaintext.contentEquals(SSH_AGENT_PLAINTEXT)) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.ssh_agent_tcp_chacha20_poly1305",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            sshAgentCiphertext.fill(0)
            sshAgentPlaintext?.fill(0)
        }

        val sshKey = NativeCrypto.ssh.generate(NativeSshKeyType.ED25519)
        var sshSignature: ByteArray? = null
        try {
            val description = NativeCrypto.ssh.describe(
                type = sshKey.type,
                privateKey = sshKey.privateKey,
                publicKey = sshKey.publicKey,
            )
            val signed = NativeCrypto.ssh.sign(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
                data = SSH_KEY_SMOKE_MESSAGE,
                flags = 0,
            )
            sshSignature = signed.signature
            if (signed.algorithm != "ssh-ed25519" || signed.signature.size != 64) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.ssh_agent_sign",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            sshKey.privateKey.fill(0)
            sshSignature?.fill(0)
        }

        val imported = NativeCrypto.ssh.importPrivateKey(OPENSSH_ED25519)
        if (imported !is NativeSshPrivateKeyImportResult.Success) {
            throw NativeCryptoException(
                operation = "packaged_smoke.ssh_private_key_import",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
        try {
            if (imported.keyMaterial.type != NativeSshKeyType.ED25519) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.ssh_private_key_import",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            imported.keyMaterial.privateKey.fill(0)
        }
        verifyEncryptedSshImport(OPENSSH_ED25519_AES256_GCM)
        verifyEncryptedSshImport(OPENSSH_ED25519_CHACHA20_POLY1305)
        verifyOpenPgpRead()
        verifyOpenPgpWrite()
        verifyOpenPgpAgentRsaDecrypt()

        return NativeCryptoDesktopSmokeResult(
            abiVersion = abiVersion,
            capabilities = capabilities.toSet(),
        )
    }

    private fun verifyEncryptedSshImport(content: String) {
        val imported = NativeCrypto.ssh.importPrivateKey(
            content = content,
            passphrase = OPENSSH_AEAD_PASSPHRASE,
        )
        if (imported !is NativeSshPrivateKeyImportResult.Success) {
            throw NativeCryptoException(
                operation = "packaged_smoke.ssh_private_key_import_aead",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
        try {
            if (
                imported.keyMaterial.type != NativeSshKeyType.ED25519 ||
                !imported.keyMaterial.publicKey.contentEquals(EXPECTED_ED25519_PUBLIC_KEY)
            ) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.ssh_private_key_import_aead",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            imported.keyMaterial.privateKey.fill(0)
        }

        val rejected = NativeCrypto.ssh.importPrivateKey(
            content = content,
            passphrase = "wrong-passphrase",
        )
        if (
            rejected != NativeSshPrivateKeyImportResult.Error(
                NativeSshPrivateKeyImportError.INVALID_PASSPHRASE,
            )
        ) {
            throw NativeCryptoException(
                operation = "packaged_smoke.ssh_private_key_import_aead_wrong_passphrase",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
    }

    private fun verifyOpenPgpRead() {
        val publicKey = OPENPGP_PUBLIC_KEY.encodeToByteArray()
        val body = OPENPGP_DETACHED_BODY.encodeToByteArray()
        val signature = OPENPGP_DETACHED_SIGNATURE.encodeToByteArray()
        try {
            val parsed = NativeCrypto.openPgp.parsePublicKeys(
                keyData = publicKey,
                referenceTimeEpochSeconds = OPENPGP_REFERENCE_TIME,
            )
            val key = (parsed as? NativeOpenPgpPublicKeyParseResult.Success)
                ?.keys
                ?.singleOrNull()
            if (
                key == null ||
                key.fingerprint != OPENPGP_PRIMARY_FINGERPRINT ||
                key.keygrip != OPENPGP_PRIMARY_KEYGRIP
            ) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.openpgp_parse",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }

            val verification = NativeCrypto.openPgp.verifyDetached(
                content = body,
                signature = signature,
                publicKeys = listOf(publicKey),
                referenceTimeEpochSeconds = OPENPGP_REFERENCE_TIME,
            )
            requireValidOpenPgpVerification(
                operation = "packaged_smoke.openpgp_verify",
                verification = verification,
            )

            NativeCrypto.openPgp.openDetachedVerification(
                signature = signature,
                publicKeys = listOf(publicKey),
                referenceTimeEpochSeconds = OPENPGP_REFERENCE_TIME,
            ).use { session ->
                val split = body.size / 2
                session.update(body, offset = 0, length = split)
                session.update(body, offset = split, length = body.size - split)
                requireValidOpenPgpVerification(
                    operation = "packaged_smoke.openpgp_verify_stream",
                    verification = session.finish(),
                )
            }
        } finally {
            publicKey.fill(0)
            body.fill(0)
            signature.fill(0)
        }
    }

    private fun verifyOpenPgpWrite() {
        val material = NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
            userId = OPENPGP_WRITE_USER_ID,
            creationTimeEpochSeconds = OPENPGP_WRITE_CREATION_TIME,
        )
        val plaintext = OPENPGP_WRITE_BODY.encodeToByteArray()
        val signingFingerprint = agentFingerprint(material, "sign")
        try {
            verifyOpenPgpMutationAndAgent(material, signingFingerprint)

            val signature = NativeCrypto.openPgp.openDetachedSigning(
                privateKey = material.privateKeyArmored,
                preferredFingerprint = signingFingerprint,
                armored = false,
                signatureTimeEpochSeconds = OPENPGP_WRITE_SIGNATURE_TIME,
                referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
            ).use { session ->
                val split = plaintext.size / 2
                session.update(plaintext, offset = 0, length = split)
                session.update(plaintext, offset = split, length = plaintext.size - split)
                session.finish()
            }
            try {
                requireGeneratedOpenPgpVerification(
                    operation = "packaged_smoke.openpgp_write_verify",
                    expectedFingerprint = signingFingerprint,
                    verification = NativeCrypto.openPgp.verifyDetached(
                        content = plaintext,
                        signature = signature,
                        publicKeys = listOf(material.publicKeyArmored),
                        referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
                    ),
                )
            } finally {
                signature.fill(0)
            }

            val encryptedChunks = mutableListOf<ByteArray>()
            val ciphertext = try {
                NativeCrypto.openPgp.openEncryption(
                    publicKeys = listOf(material.publicKeyArmored),
                    signingPrivateKey = material.privateKeyArmored,
                    preferredSigningFingerprint = signingFingerprint,
                    fileName = OPENPGP_WRITE_FILE_NAME,
                    armored = false,
                    literalTimeEpochSeconds = OPENPGP_WRITE_SIGNATURE_TIME,
                    referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
                ).use { session ->
                    val split = plaintext.size / 2
                    encryptedChunks += session.update(plaintext, offset = 0, length = split)
                    encryptedChunks += session.update(
                        plaintext,
                        offset = split,
                        length = plaintext.size - split,
                    )
                    val final = session.finish()
                    if (final.protectionMode != NativeOpenPgpProtectionMode.GNUPG_OCB) {
                        throw NativeCryptoException(
                            operation = "packaged_smoke.openpgp_write_ocb",
                            code = NativeCryptoErrorCode.INTERNAL,
                        )
                    }
                    encryptedChunks += final.data
                }
                joinChunks(encryptedChunks)
            } finally {
                encryptedChunks.forEach { chunk -> chunk.fill(0) }
            }
            try {
                verifyOpenPgpAgentEcdhDecrypt(material, ciphertext)
                val provisionalPlaintext = mutableListOf<ByteArray>()
                val decrypted = try {
                    val verification = NativeCrypto.openPgp.openDecryption(
                        privateKeys = listOf(material.privateKeyArmored),
                        verificationPublicKeys = listOf(material.publicKeyArmored),
                        referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
                    ).use { session ->
                        val split = ciphertext.size / 2
                        provisionalPlaintext += session.update(ciphertext, offset = 0, length = split)
                        provisionalPlaintext += session.update(
                            ciphertext,
                            offset = split,
                            length = ciphertext.size - split,
                        )
                        val final = session.finish()
                        provisionalPlaintext += final.data
                        final.verification
                    }
                    requireGeneratedOpenPgpVerification(
                        operation = "packaged_smoke.openpgp_write_decrypt_verify",
                        expectedFingerprint = signingFingerprint,
                        verification = verification,
                    )
                    joinChunks(provisionalPlaintext)
                } finally {
                    provisionalPlaintext.forEach { chunk -> chunk.fill(0) }
                }
                try {
                    if (!decrypted.contentEquals(plaintext)) {
                        throw NativeCryptoException(
                            operation = "packaged_smoke.openpgp_write_roundtrip",
                            code = NativeCryptoErrorCode.INTERNAL,
                        )
                    }
                } finally {
                    decrypted.fill(0)
                }
            } finally {
                ciphertext.fill(0)
            }
        } finally {
            plaintext.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    private fun verifyOpenPgpAgentEcdhDecrypt(
        material: NativeOpenPgpKeyMaterial,
        ciphertext: ByteArray,
    ) {
        val body = firstPkeskBody(ciphertext)
        try {
            if (body.size < PKESK_PREFIX_BYTES || body[PKESK_ALGORITHM_OFFSET] != ECDH_ALGORITHM) {
                throw packagedSmokeFailure("openpgp_agent_ecdh_decrypt")
            }
            val (ephemeral, wrappedOffset) = readMpi(body, PKESK_PREFIX_BYTES)
            val wrapped = body.copyOfRange(wrappedOffset, body.size)
            val encVal = canonicalEncVal(
                algorithm = "ecdh",
                parameters = listOf("e" to ephemeral, "s" to wrapped),
            )
            try {
                val canonical = successfulAgentDecrypt(
                    operation = "openpgp_agent_ecdh_decrypt",
                    material = material,
                    ciphertext = encVal,
                    unwrapEcdh = true,
                )
                try {
                    val value = canonicalValue(canonical)
                    try {
                        if (value.isEmpty() || value.size % 8 != 0) {
                            throw packagedSmokeFailure("openpgp_agent_ecdh_decrypt")
                        }
                    } finally {
                        value.fill(0)
                    }
                } finally {
                    canonical.fill(0)
                }
            } finally {
                encVal.fill(0)
                ephemeral.fill(0)
                wrapped.fill(0)
            }
        } finally {
            body.fill(0)
        }
    }

    private fun verifyOpenPgpAgentRsaDecrypt() {
        val material = NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.RSA,
            userId = OPENPGP_RSA_AGENT_USER_ID,
            rsaBits = 3_072,
            creationTimeEpochSeconds = OPENPGP_WRITE_CREATION_TIME,
        )
        val plaintext = byteArrayOf(0x2a)
        var ciphertext: ByteArray? = null
        try {
            ciphertext = NativeCrypto.openPgp.encrypt(
                content = plaintext,
                publicKeys = listOf(material.publicKeyArmored),
                fileName = OPENPGP_RSA_AGENT_FILE_NAME,
                armored = false,
                literalTimeEpochSeconds = OPENPGP_WRITE_SIGNATURE_TIME,
                referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
            ).data
            val body = firstPkeskBody(ciphertext)
            try {
                if (body.size < PKESK_PREFIX_BYTES || body[PKESK_ALGORITHM_OFFSET] != RSA_ALGORITHM) {
                    throw packagedSmokeFailure("openpgp_agent_rsa_decrypt")
                }
                val (encryptedSessionKey, end) = readMpi(body, PKESK_PREFIX_BYTES)
                if (end != body.size) {
                    encryptedSessionKey.fill(0)
                    throw packagedSmokeFailure("openpgp_agent_rsa_decrypt")
                }
                val encVal = canonicalEncVal(
                    algorithm = "rsa",
                    parameters = listOf("a" to encryptedSessionKey),
                )
                try {
                    val canonical = successfulAgentDecrypt(
                        operation = "openpgp_agent_rsa_decrypt",
                        material = material,
                        ciphertext = encVal,
                        unwrapEcdh = false,
                    )
                    try {
                        val value = canonicalValue(canonical)
                        try {
                            // The raw private operation returns the PKCS#1 v1.5
                            // encryption block without its leading zero octet.
                            val separator = (9 until value.size)
                                .firstOrNull { index -> value[index] == 0.toByte() }
                                ?: -1
                            if (value.firstOrNull() != 0x02.toByte() || separator < 9) {
                                throw packagedSmokeFailure("openpgp_agent_rsa_decrypt")
                            }
                        } finally {
                            value.fill(0)
                        }
                    } finally {
                        canonical.fill(0)
                    }
                } finally {
                    encVal.fill(0)
                    encryptedSessionKey.fill(0)
                }
            } finally {
                body.fill(0)
            }
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    private fun successfulAgentDecrypt(
        operation: String,
        material: NativeOpenPgpKeyMaterial,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): ByteArray {
        val fingerprint = agentFingerprint(material, "decrypt")
        val result = NativeCrypto.openPgp.agentDecrypt(
            privateKey = material.privateKeyArmored,
            preferredFingerprint = fingerprint,
            ciphertext = ciphertext,
            unwrapEcdh = unwrapEcdh,
        )
        return (result as? NativeOpenPgpAgentDecryptResult.Success)?.canonicalSexp
            ?: throw packagedSmokeFailure(operation)
    }

    private fun agentFingerprint(
        material: NativeOpenPgpKeyMaterial,
        capability: String,
    ): String {
        val metadata = NativeCrypto.openPgp.resolveMetadata(
            privateKeyData = material.privateKeyArmored,
            publicKeyData = material.publicKeyArmored,
            normalizedFingerprint = material.fingerprint,
            referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
        ) ?: throw packagedSmokeFailure("openpgp_agent_${capability}_key")
        return metadata.keys.singleOrNull { key -> capability in key.capabilities }
            ?.fingerprint
            ?: throw packagedSmokeFailure("openpgp_agent_${capability}_key")
    }

    private fun firstPkeskBody(message: ByteArray): ByteArray {
        if (message.isEmpty()) throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        val header = message[0].toInt() and 0xff
        if (header and 0x80 == 0) throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        val (tag, length, bodyOffset) = if (header and 0x40 != 0) {
            val (length, offset) = readNewPacketLength(message, 1)
            Triple(header and 0x3f, length, offset)
        } else {
            val tag = (header ushr 2) and 0x0f
            val (length, offset) = readOldPacketLength(message, 1, header and 0x03)
            Triple(tag, length, offset)
        }
        val bodyEnd = bodyOffset.toLong() + length.toLong()
        if (tag != PKESK_PACKET_TAG || bodyEnd > message.size || bodyEnd < bodyOffset) {
            throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        }
        return message.copyOfRange(bodyOffset, bodyEnd.toInt())
    }

    private fun readNewPacketLength(input: ByteArray, offset: Int): Pair<Int, Int> {
        val first = input.getOrNull(offset)?.toInt()?.and(0xff)
            ?: throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        return when {
            first < 192 -> first to offset + 1
            first < 224 -> {
                val second = input.getOrNull(offset + 1)?.toInt()?.and(0xff)
                    ?: throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
                ((first - 192) shl 8) + second + 192 to offset + 2
            }

            first == 255 -> readUint32Length(input, offset + 1) to offset + 5
            else -> throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        }
    }

    private fun readOldPacketLength(
        input: ByteArray,
        offset: Int,
        lengthType: Int,
    ): Pair<Int, Int> = when (lengthType) {
        0 -> (input.getOrNull(offset)?.toInt()?.and(0xff)
            ?: throw packagedSmokeFailure("openpgp_agent_decrypt_packet")) to offset + 1

        1 -> {
            val high = input.getOrNull(offset)?.toInt()?.and(0xff)
                ?: throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
            val low = input.getOrNull(offset + 1)?.toInt()?.and(0xff)
                ?: throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
            ((high shl 8) or low) to offset + 2
        }

        2 -> readUint32Length(input, offset) to offset + 4
        else -> throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
    }

    private fun readUint32Length(input: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > input.size) {
            throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        }
        val value = (0 until 4).fold(0L) { length, index ->
            (length shl 8) or (input[offset + index].toLong() and 0xff)
        }
        if (value > Int.MAX_VALUE) throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        return value.toInt()
    }

    private fun readMpi(input: ByteArray, offset: Int): Pair<ByteArray, Int> {
        if (offset < 0 || offset + 2 > input.size) {
            throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        }
        val bits = ((input[offset].toInt() and 0xff) shl 8) or
            (input[offset + 1].toInt() and 0xff)
        val size = (bits + 7) / 8
        val end = offset + 2 + size
        if (end < offset || end > input.size) {
            throw packagedSmokeFailure("openpgp_agent_decrypt_packet")
        }
        return input.copyOfRange(offset + 2, end) to end
    }

    private fun canonicalEncVal(
        algorithm: String,
        parameters: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        fun atom(value: ByteArray) {
            output.write(value.size.toString().encodeToByteArray())
            output.write(':'.code)
            output.write(value)
        }
        output.write('('.code)
        atom("enc-val".encodeToByteArray())
        output.write('('.code)
        atom(algorithm.encodeToByteArray())
        parameters.forEach { (name, value) ->
            output.write('('.code)
            atom(name.encodeToByteArray())
            atom(value)
            output.write(')'.code)
        }
        output.write(')'.code)
        output.write(')'.code)
        return output.toByteArray()
    }

    private fun canonicalValue(input: ByteArray): ByteArray {
        var offset = 0
        fun requireByte(expected: Int) {
            if (input.getOrNull(offset)?.toInt()?.and(0xff) != expected) {
                throw packagedSmokeFailure("openpgp_agent_decrypt_response")
            }
            offset += 1
        }
        fun atom(): ByteArray {
            val digitsStart = offset
            while (true) {
                val value = input.getOrNull(offset)?.toInt()?.and(0xff) ?: break
                if (value !in '0'.code..'9'.code) break
                offset += 1
            }
            if (offset == digitsStart || input.getOrNull(offset) != ':'.code.toByte()) {
                throw packagedSmokeFailure("openpgp_agent_decrypt_response")
            }
            val length = input.copyOfRange(digitsStart, offset).decodeToString().toIntOrNull()
                ?: throw packagedSmokeFailure("openpgp_agent_decrypt_response")
            offset += 1
            val end = offset + length
            if (end < offset || end > input.size) {
                throw packagedSmokeFailure("openpgp_agent_decrypt_response")
            }
            return input.copyOfRange(offset, end).also { offset = end }
        }
        requireByte('('.code)
        val name = atom()
        try {
            if (!name.contentEquals("value".encodeToByteArray())) {
                throw packagedSmokeFailure("openpgp_agent_decrypt_response")
            }
        } finally {
            name.fill(0)
        }
        val value = atom()
        requireByte(')'.code)
        if (offset != input.size) {
            value.fill(0)
            throw packagedSmokeFailure("openpgp_agent_decrypt_response")
        }
        return value
    }

    private fun packagedSmokeFailure(operation: String): NativeCryptoException =
        NativeCryptoException(
            operation = "packaged_smoke.$operation",
            code = NativeCryptoErrorCode.INTERNAL,
        )

    private fun verifyOpenPgpMutationAndAgent(
        material: NativeOpenPgpKeyMaterial,
        signingFingerprint: String,
    ) {
        val expiration = NativeCrypto.openPgp.updateExpiration(
            privateKey = material.privateKeyArmored,
            publicKey = material.publicKeyArmored,
            expectedPrimaryFingerprint = material.fingerprint,
            componentFingerprints = listOf(material.fingerprint),
            expiresAtEpochSeconds = OPENPGP_WRITE_EXPIRATION_TIME,
            candidateRevocationKeys = emptyList(),
            referenceTimeEpochSeconds = OPENPGP_WRITE_REFERENCE_TIME,
        )
        val updated = expiration as? NativeOpenPgpExpirationUpdateResult.Success
            ?: throw NativeCryptoException(
                operation = "packaged_smoke.openpgp_expiration_update",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        try {
            if (
                updated.keyMaterial.fingerprint != material.fingerprint ||
                updated.metadata.keys.none { key -> key.fingerprint == material.fingerprint }
            ) {
                throw NativeCryptoException(
                    operation = "packaged_smoke.openpgp_expiration_update",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            }
        } finally {
            updated.keyMaterial.privateKeyArmored.fill(0)
            updated.keyMaterial.publicKeyArmored.fill(0)
        }

        val hash = ByteArray(32) { index -> index.toByte() }
        try {
            val signed = NativeCrypto.openPgp.agentSignHash(
                privateKey = material.privateKeyArmored,
                preferredFingerprint = signingFingerprint,
                hashAlgorithm = "sha256",
                hash = hash,
            )
            val signature = (signed as? NativeOpenPgpAgentSignResult.Success)?.canonicalSexp
                ?: throw NativeCryptoException(
                    operation = "packaged_smoke.openpgp_agent_sign",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            signature.fill(0)
        } finally {
            hash.fill(0)
        }

        val decrypt = NativeCrypto.openPgp.agentDecrypt(
            privateKey = material.privateKeyArmored,
            preferredFingerprint = MISSING_OPENPGP_FINGERPRINT,
            ciphertext = byteArrayOf(),
            unwrapEcdh = false,
        )
        if (
            decrypt != NativeOpenPgpAgentDecryptResult.Error(
                NativeOpenPgpAgentError.KEY_NOT_FOUND,
            )
        ) {
            (decrypt as? NativeOpenPgpAgentDecryptResult.Success)?.canonicalSexp?.fill(0)
            throw NativeCryptoException(
                operation = "packaged_smoke.openpgp_agent_decrypt",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
    }

    private fun requireGeneratedOpenPgpVerification(
        operation: String,
        expectedFingerprint: String,
        verification: NativeOpenPgpVerification?,
    ) {
        if (
            verification == null ||
            verification.status != NativeOpenPgpVerificationStatus.VALID ||
            verification.fingerprint != expectedFingerprint
        ) {
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
    }

    private fun joinChunks(chunks: List<ByteArray>): ByteArray {
        val size = chunks.sumOf { chunk -> chunk.size }
        val output = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }
        return output
    }

    private fun requireValidOpenPgpVerification(
        operation: String,
        verification: NativeOpenPgpVerification,
    ) {
        if (
            verification.status != NativeOpenPgpVerificationStatus.VALID ||
            verification.fingerprint != OPENPGP_PRIMARY_FINGERPRINT ||
            verification.keyId != "F83D947D29EFECF7"
        ) {
            throw NativeCryptoException(
                operation = operation,
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
    }

    private fun requirePackagedLibrary() {
        val configuredLibrary = System.getProperty("keyguard.nativeCrypto.libraryPath")
        if (!configuredLibrary.isNullOrBlank()) {
            throw NativeCryptoException(
                operation = "packaged_smoke.resources",
                code = NativeCryptoErrorCode.INVALID_ARGUMENT,
            )
        }
    }

    private fun loadPackagedLibrary() {
        try {
            NativeCryptoLibraryLoader.ensureBundledLibraryLoaded()
        } catch (e: NativeCryptoPlatformException) {
            throw NativeCryptoException(
                operation = "packaged_smoke.load",
                code = e.code,
            )
        } catch (_: Exception) {
            throw NativeCryptoException(
                operation = "packaged_smoke.load",
                code = NativeCryptoErrorCode.INTERNAL,
            )
        }
    }

    private val EXPECTED_SHA256_ABC = byteArrayOf(
        0xba.toByte(), 0x78, 0x16, 0xbf.toByte(), 0x8f.toByte(), 0x01, 0xcf.toByte(), 0xea.toByte(),
        0x41, 0x41, 0x40, 0xde.toByte(), 0x5d, 0xae.toByte(), 0x22, 0x23,
        0xb0.toByte(), 0x03, 0x61, 0xa3.toByte(), 0x96.toByte(), 0x17, 0x7a, 0x9c.toByte(),
        0xb4.toByte(), 0x10, 0xff.toByte(), 0x61, 0xf2.toByte(), 0x00, 0x15, 0xad.toByte(),
    )
    private val SSH_AGENT_NONCE = hex("a0a1a2a30000000000000001")
    private val SSH_AGENT_HEADER = hex("4b5341470203000000000000000100000028")
    private val SSH_AGENT_PLAINTEXT = "keyguard-ssh-agent-frame".encodeToByteArray()
    private val SSH_KEY_SMOKE_MESSAGE = "keyguard-ssh-key-sign".encodeToByteArray()
    private val EXPECTED_SSH_AGENT_CIPHERTEXT = hex(
        "4cb94ca92fd4281424e0b87c31a8a7cbabb723966ade916ef50ed0595bcf22b4" +
            "b63cd9fd80bc498b",
    )
    private val EXPECTED_ED25519_PUBLIC_KEY = hex(
        "0000000b7373682d6564323535313900000020" +
            "b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62",
    )
    private const val OPENPGP_REFERENCE_TIME = 1_783_944_100L
    private const val OPENPGP_PRIMARY_FINGERPRINT =
        "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
    private const val OPENPGP_PRIMARY_KEYGRIP =
        "894264A490F8D55E3E28378A7E44373782806220"
    private const val OPENPGP_WRITE_CREATION_TIME = 1_700_000_000L
    private const val OPENPGP_WRITE_SIGNATURE_TIME = OPENPGP_WRITE_CREATION_TIME + 60L
    private const val OPENPGP_WRITE_REFERENCE_TIME = OPENPGP_WRITE_CREATION_TIME + 120L
    private const val OPENPGP_WRITE_EXPIRATION_TIME = OPENPGP_WRITE_CREATION_TIME + 86_400L
    private const val MISSING_OPENPGP_FINGERPRINT =
        "0000000000000000000000000000000000000000"
    private const val OPENPGP_WRITE_USER_ID =
        "Keyguard packaged OpenPGP write smoke <openpgp-write-smoke@test.invalid>"
    private const val OPENPGP_WRITE_FILE_NAME = "openpgp-write-smoke.txt"
    private const val OPENPGP_WRITE_BODY = "Keyguard packaged OpenPGP authenticated roundtrip."
    private const val OPENPGP_RSA_AGENT_USER_ID =
        "Keyguard packaged OpenPGP RSA agent smoke <openpgp-rsa@test.invalid>"
    private const val OPENPGP_RSA_AGENT_FILE_NAME = "openpgp-rsa-smoke.bin"
    private const val PKESK_PACKET_TAG = 1
    private const val PKESK_ALGORITHM_OFFSET = 9
    private const val PKESK_PREFIX_BYTES = 10
    private const val RSA_ALGORITHM: Byte = 1
    private const val ECDH_ALGORITHM: Byte = 18
    private val OPENPGP_PUBLIC_KEY = """
        -----BEGIN PGP PUBLIC KEY BLOCK-----

        mDMEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
        Zq0b1Q60LEtleWd1YXJkIFRlc3QgQ1YyNTUxOSA8Y3YyNTUxOUB0ZXN0LmludmFs
        aWQ+iK8EExYKAFcWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCaj9rzxsUgAAAAAAE
        AA5tYW51MiwyLjUrMS4xMiwwLDMCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwECHgcC
        F4AACgkQ+D2UfSnv7PezOQD+JMrO7BD9rfc1ciIZoSW5NCw9N+8tkU8fOxKsdFQ+
        0DEA/iZ7e3W2CRUGtt8UTHwzBLZOlgn5Ox4O/49/6/Cn92gEuDgEaj9r7BIKKwYB
        BAGXVQEFAQEHQFzTFZW3PHTv8qstyY8CdxMH7TZJnkpIutnhRc7xun12AwEIB4iU
        BBgWCgA8FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a+wbFIAAAAAABAAObWFu
        dTIsMi41KzEuMTIsMCwzAhsMAAoJEPg9lH0p7+z3LpQA/09tlKbt7+j26p+QwbCs
        bu8oruCxbNY45226eyy6QxS9AQC6cwXPn1NewS7XjGGKea14CgjpvqstWe9PiyfJ
        Y7c+CA==
        =Kf2G
        -----END PGP PUBLIC KEY BLOCK-----
    """.trimIndent() + "\n"
    private val OPENPGP_DETACHED_BODY = """
        Independent OpenPGP verification fixture.
        Second line.
    """.trimIndent() + "\n"
    private val OPENPGP_DETACHED_SIGNATURE = """
        -----BEGIN PGP SIGNATURE-----

        iJEEABYKADkWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCalbNgBsUgAAAAAAEAA5t
        YW51MiwyLjUrMS4xMiwwLDMACgkQ+D2UfSnv7Pe4sQEAowtp7N4njm4eBEi+bgC1
        VxGYWoE70RB//wCTrwaVtggBAL3MVySwcv/iU0y9pM+91TaerHhzhSNnDjcJTS4d
        SOEL
        =6B1K
        -----END PGP SIGNATURE-----
    """.trimIndent() + "\n"
    private const val OPENSSH_AEAD_PASSPHRASE = "hunter42"
    private const val OPENSSH_ED25519 = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJgAIAxdACAM
XQAAAAtzc2gtZWQyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYg
AAAEC2BsIi0QwW2uFscKTUUXNHLsYX4FxlaSDSblbAj7WR7bM+rvN+ot98qgEN796jTiQf
ZfG1KaT0PtFDJ/XFSqtiAAAAEHVzZXJAZXhhbXBsZS5jb20BAgMEBQ==
-----END OPENSSH PRIVATE KEY-----"""

    private const val OPENSSH_ED25519_AES256_GCM = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAAFmFlczI1Ni1nY21Ab3BlbnNzaC5jb20AAAAGYmNyeXB0AA
AAGAAAABARvcEz72RkQRWxdpF+R8uvAAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAA
ILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqtiAAAAoIJQm81qpEdHOG7cGK5d27
FAelmbS6xxp7YaqYnD+9agVk6KsbAM8SMDF6AEiVaxoVPX/+HRV1HwA5BRpWijXmC6meyV
604UAY1ubJKemubnSrNSa4slV/r6wLut1vqFD8ro6nobT+wCgUrwDsL7ZI/9i6nQYXFdDS
vKbSu+2Nwh3B78JQoZXyetXQy3fOZKqrvy/6BFRDsOTKckfRCiAaTcNzfq+DH3OG5x+brH
Yl4J
-----END OPENSSH PRIVATE KEY-----"""

    private const val OPENSSH_ED25519_CHACHA20_POLY1305 = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAAHWNoYWNoYTIwLXBvbHkxMzA1QG9wZW5zc2guY29tAAAABm
JjcnlwdAAAABgAAAAQ9lHKPvsVkE0FwhalBB6omgAAABAAAAABAAAAMwAAAAtzc2gtZWQy
NTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJiRvYDd00XU/W
BkZ93ZW52HNwvM2m3z/MHuqD8q/tk16rKKtBNOc95wo4gyRzkdGYhKnF1RFCJYcdvlw6zo
kctfmmhQ6W54G6u9Eh9bIJtHt3l4FQgzriuIsBTUKZIlvvk6Fo5ItNPHM00r2ehuX81lcZ
QHMaims6Blw8Esl6G3NYCAa2NKyqlmM5LIfkga/Ymydvrbc7EQmN2hbii0c0aMUdYQclyk
F4o=
-----END OPENSSH PRIVATE KEY-----"""

    private fun hex(value: String): ByteArray = value
        .chunked(2)
        .map { byte -> byte.toInt(16).toByte() }
        .toByteArray()
}
