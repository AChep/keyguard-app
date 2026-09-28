package com.artemchep.keyguard.util.yubikey

internal const val YUBIKEY_MAX_CHALLENGE_LENGTH = 64
internal const val YUBIKEY_SECRET_LENGTH = 20
internal const val YUBIKEY_RESPONSE_LENGTH = 20

/** Device operations only: no vault, account, UI, or persistence policy. */
sealed class YubiKeyOperation(val slot: Int) {
    init { require(slot == 1 || slot == 2) }

    class Inspect(slot: Int) : YubiKeyOperation(slot)
    class ChallengeResponse(slot: Int, val challenge: ByteArray) : YubiKeyOperation(slot) {
        init { require(challenge.size in 1..YUBIKEY_MAX_CHALLENGE_LENGTH) }
    }
    class Provision(
        slot: Int,
        val challenge: ByteArray,
        val secret: ByteArray,
        val overwrite: Boolean,
        val requireTouch: Boolean = true,
    ) : YubiKeyOperation(slot) {
        init {
            require(challenge.size in 1..YUBIKEY_MAX_CHALLENGE_LENGTH)
            require(secret.size == YUBIKEY_SECRET_LENGTH)
        }
    }
}

sealed interface YubiKeyResult {
    data class SlotStatus(val configured: Boolean) : YubiKeyResult
    class Response(val bytes: ByteArray) : YubiKeyResult
}

/** A canceled coroutine must cancel an in-flight device operation. */
fun interface YubiKeyClient {
    suspend fun execute(operation: YubiKeyOperation): YubiKeyResult
}
