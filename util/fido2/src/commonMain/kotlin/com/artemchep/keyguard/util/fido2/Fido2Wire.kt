@file:Suppress("MagicNumber") // Bounded binary ABI, version 2.

package com.artemchep.keyguard.util.fido2

private const val CHALLENGE_OFFSET = 4
private const val INPUT_OFFSET = CHALLENGE_OFFSET + FIDO2_INPUT_LENGTH
private const val HEADER_LENGTH = INPUT_OFFSET + FIDO2_INPUT_LENGTH

internal fun encodeFido2Request(operation: Fido2Operation, pin: String?): ByteArray {
    val pinBytes = normalizeFido2Pin(pin.orEmpty()).encodeToByteArray()
    try {
        require(pinBytes.size <= FIDO2_MAX_PIN_LENGTH && pinBytes.none { it == 0.toByte() })
        if (operation is Fido2Operation.Assert) return encodeAssertionRequest(operation.request, pinBytes)
        val (opcode, input, credential) =
            when (operation) {
                is Fido2Operation.Register -> Triple(1, operation.userId, byteArrayOf())
                is Fido2Operation.Derive -> Triple(2, operation.salt, operation.credentialId)
                is Fido2Operation.Assert -> error("Assertion was encoded above")
            }
        return ByteArray(HEADER_LENGTH + credential.size + pinBytes.size).apply {
            this[0] = opcode.toByte()
            this[1] = (credential.size shr 8).toByte()
            this[2] = credential.size.toByte()
            this[3] = pinBytes.size.toByte()
            operation.challenge.copyInto(this, CHALLENGE_OFFSET)
            input.copyInto(this, INPUT_OFFSET)
            credential.copyInto(this, HEADER_LENGTH)
            pinBytes.copyInto(this, HEADER_LENGTH + credential.size)
        }
    } finally {
        pinBytes.fill(0)
    }
}

internal fun decodeFido2Response(operation: Fido2Operation, bytes: ByteArray): ByteArray {
    val code = bytes.firstOrNull()?.toInt() ?: Fido2Failure.PROTOCOL.code
    if (code != 0)
        throw Fido2Exception(
            Fido2Failure.entries.firstOrNull { it.code == code } ?: Fido2Failure.PROTOCOL
        )
    val valid =
        when (operation) {
            is Fido2Operation.Register -> bytes.size in 2..(FIDO2_MAX_CREDENTIAL_LENGTH + 1)
            is Fido2Operation.Derive -> bytes.size == FIDO2_SECRET_LENGTH + 1
            is Fido2Operation.Assert -> bytes.size in 2..FIDO2_MAX_RESPONSE
        }
    if (!valid) throw Fido2Exception(Fido2Failure.PROTOCOL)
    return bytes.copyOfRange(1, bytes.size)
}
