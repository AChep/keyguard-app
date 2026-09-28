@file:Suppress("MagicNumber") // Fixed byte offsets and operation tags in ABI version 1.

package com.artemchep.keyguard.util.yubikey

internal const val YUBIKEY_ABI_VERSION = 1

internal fun encodeYubiKeyRequest(operation: YubiKeyOperation): ByteArray = when (operation) {
    is YubiKeyOperation.Inspect -> encodeRequest(1, operation.slot)
    is YubiKeyOperation.ChallengeResponse -> encodeRequest(2, operation.slot, challenge = operation.challenge)
    is YubiKeyOperation.Provision -> {
        val flags = (if (operation.overwrite) 1 else 0) or (if (operation.requireTouch) 2 else 0)
        encodeRequest(3, operation.slot, flags, operation.challenge, operation.secret)
    }
}

private fun encodeRequest(
    command: Int,
    slot: Int,
    flags: Int = 0,
    challenge: ByteArray = byteArrayOf(),
    secret: ByteArray = byteArrayOf(),
) = ByteArray(4 + challenge.size + secret.size).apply {
    this[0] = command.toByte()
    this[1] = slot.toByte()
    this[2] = flags.toByte()
    this[3] = challenge.size.toByte()
    challenge.copyInto(this, 4)
    secret.copyInto(this, 4 + challenge.size)
}

@Suppress("ThrowsCount") // Reject malformed status, discriminants, and payload lengths at this boundary.
internal fun decodeYubiKeyResponse(operation: YubiKeyOperation, bytes: ByteArray): YubiKeyResult {
    val code = bytes.firstOrNull()?.toInt()
        ?: throw YubiKeyException(YubiKeyFailure.PROTOCOL)
    if (code != 0) {
        val failure = YubiKeyFailure.entries.firstOrNull { it.code == code }
            ?: YubiKeyFailure.PROTOCOL
        throw YubiKeyException(failure)
    }
    return if (operation is YubiKeyOperation.Inspect) {
        if (bytes.size != 2 || bytes[1].toInt() !in 0..1) {
            throw YubiKeyException(YubiKeyFailure.PROTOCOL)
        }
        YubiKeyResult.SlotStatus(bytes[1].toInt() == 1)
    } else {
        if (bytes.size != YUBIKEY_RESPONSE_LENGTH + 1) throw YubiKeyException(YubiKeyFailure.PROTOCOL)
        YubiKeyResult.Response(bytes.copyOfRange(1, bytes.size))
    }
}
