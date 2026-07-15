package com.artemchep.keyguard.nativecrypto

internal expect object NativeCryptoLibraryLoader {
    fun ensureLoaded()
}

internal actual object NativeCryptoPlatform : NativeCryptoBridge {
    actual override fun abiVersion(): Int = withLibrary { NativeCryptoJni.abiVersion() }

    actual override fun capabilities(): Long = withLibrary { NativeCryptoJni.capabilities() }

    actual override fun randomInt(exclusiveUpperBound: Int): Long = withLibrary {
        NativeCryptoJni.randomInt(exclusiveUpperBound)
    }

    actual override fun call(request: ByteArray): ByteArray = withLibrary {
        NativeCryptoJni.call(request)
    }

    actual override fun streamOpen(request: ByteArray): ByteArray = withLibrary {
        NativeCryptoJni.streamOpen(request)
    }

    actual override fun streamUpdate(handle: Long, input: ByteArray): ByteArray = withLibrary {
        NativeCryptoJni.streamUpdate(handle, input)
    }

    actual override fun streamFinish(handle: Long): ByteArray = withLibrary {
        NativeCryptoJni.streamFinish(handle)
    }

    actual override fun streamClose(handle: Long): ByteArray = withLibrary {
        NativeCryptoJni.streamClose(handle)
    }

    actual override fun aesCbcPkcs7HmacSha256Encrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        ciphertextOutput: ByteArray,
        macOutput: ByteArray,
    ): Long = withLibrary {
        NativeCryptoJni.aesCbcPkcs7HmacSha256Encrypt(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            plaintext = plaintext,
            ciphertextOutput = ciphertextOutput,
            macOutput = macOutput,
        )
    }

    actual override fun aesCbcPkcs7HmacSha256Decrypt(
        encryptionKey: ByteArray,
        macKey: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        expectedMac: ByteArray,
        plaintextOutput: ByteArray,
    ): Long = withLibrary {
        NativeCryptoJni.aesCbcPkcs7HmacSha256Decrypt(
            encryptionKey = encryptionKey,
            macKey = macKey,
            iv = iv,
            ciphertext = ciphertext,
            expectedMac = expectedMac,
            plaintextOutput = plaintextOutput,
        )
    }

    private inline fun <T> withLibrary(block: () -> T): T {
        NativeCryptoLibraryLoader.ensureLoaded()
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
        } catch (e: SecurityException) {
            throw NativeCryptoPlatformException(NativeCryptoErrorCode.LIBRARY_UNAVAILABLE, e)
        }
    }
}
