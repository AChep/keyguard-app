package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_invalid_input
import org.jetbrains.compose.resources.StringResource

internal fun Throwable.gpgToolsInputErrorResource(
    toolOperation: GpgToolsOperation,
): StringResource? {
    val failure = this as? NativeCryptoException ?: return null
    if (failure.code != NativeCryptoErrorCode.INVALID_ARGUMENT) {
        return null
    }
    val parsesUntrustedInput = when (toolOperation) {
        GpgToolsOperation.DECRYPT -> failure.operation in DECRYPT_INPUT_OPERATIONS
        GpgToolsOperation.VERIFY -> failure.operation in VERIFY_INPUT_OPERATIONS
        GpgToolsOperation.SIGN,
        GpgToolsOperation.ENCRYPT,
        -> false
    }
    return if (parsesUntrustedInput) {
        Res.string.gpg_tools_invalid_input
    } else {
        null
    }
}

/** Native operations whose invalid argument is supplied by the GPG tools input fields. */
private val DECRYPT_INPUT_OPERATIONS = setOf(
    "open_pgp_decrypt",
    "stream.update",
    "open_pgp_stream_drain",
    "stream.finish",
)

private val VERIFY_INPUT_OPERATIONS = setOf(
    "open_pgp_verify",
    "open_pgp_detached_verify.stream_open",
)
