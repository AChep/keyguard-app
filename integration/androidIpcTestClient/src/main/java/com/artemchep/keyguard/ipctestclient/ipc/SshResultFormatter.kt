package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.SshAuthenticationApiError

fun sshResultCodeName(code: Int): String = when (code) {
    SshAuthenticationApi.RESULT_CODE_ERROR -> "ERROR"
    SshAuthenticationApi.RESULT_CODE_SUCCESS -> "SUCCESS"
    SshAuthenticationApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
        "USER_INTERACTION_REQUIRED"

    else -> "UNKNOWN($code)"
}

@Suppress("CyclomaticComplexMethod")
fun sshErrorName(error: Int): String = when (error) {
    SshAuthenticationApiError.CLIENT_SIDE_ERROR -> "CLIENT_SIDE_ERROR"
    SshAuthenticationApiError.GENERIC_ERROR -> "GENERIC_ERROR"
    SshAuthenticationApiError.INCOMPATIBLE_API_VERSIONS -> "INCOMPATIBLE_API_VERSIONS"
    SshAuthenticationApiError.INTERNAL_ERROR -> "INTERNAL_ERROR"
    SshAuthenticationApiError.UNKNOWN_ACTION -> "UNKNOWN_ACTION"
    SshAuthenticationApiError.NO_KEY_ID -> "NO_KEY_ID"
    SshAuthenticationApiError.NO_SUCH_KEY -> "NO_SUCH_KEY"
    SshAuthenticationApiError.NO_AUTH_KEY -> "NO_AUTH_KEY"
    SshAuthenticationApiError.INVALID_ALGORITHM -> "INVALID_ALGORITHM"
    SshAuthenticationApiError.INVALID_HASH_ALGORITHM -> "INVALID_HASH_ALGORITHM"
    else -> "UNKNOWN($error)"
}

/** Decodes every result extra the SSH Authentication API defines. */
@Suppress("DEPRECATION")
fun formatSshResult(result: Intent): String = buildString {
    val code = result.getIntExtra(
        SshAuthenticationApi.EXTRA_RESULT_CODE,
        IpcExchange.UNKNOWN_RESULT_CODE,
    )
    appendLine("result_code = ${sshResultCodeName(code)}")
    result
        .getParcelableExtra<SshAuthenticationApiError>(SshAuthenticationApi.EXTRA_ERROR)
        ?.let { appendLine("error = ${sshErrorName(it.error)}: ${it.message}") }
    if (result.hasExtra(SshAuthenticationApi.EXTRA_PENDING_INTENT)) {
        appendLine("intent = PendingIntent (user interaction)")
    }
    result.getStringExtra(SshAuthenticationApi.EXTRA_KEY_ID)?.let {
        appendLine("key_id = $it")
    }
    result.getStringExtra(SshAuthenticationApi.EXTRA_KEY_DESCRIPTION)?.let {
        appendLine("key_description = $it")
    }
    appendPublicKey(result)
    appendSignature(result)
}

private fun StringBuilder.appendPublicKey(result: Intent) {
    result.getByteArrayExtra(SshAuthenticationApi.EXTRA_PUBLIC_KEY)?.let {
        appendLine("public_key = X.509 SPKI DER, byte[${it.size}]")
        appendLine("  ${it.toHex()}")
    }
    if (result.hasExtra(SshAuthenticationApi.EXTRA_PUBLIC_KEY_ALGORITHM)) {
        val algorithm = result.getIntExtra(
            SshAuthenticationApi.EXTRA_PUBLIC_KEY_ALGORITHM,
            IpcExchange.UNKNOWN_RESULT_CODE,
        )
        appendLine("public_key_algorithm = ${sshKeyAlgorithmName(algorithm)}")
    }
    result.getStringExtra(SshAuthenticationApi.EXTRA_SSH_PUBLIC_KEY)?.let { line ->
        appendLine("ssh_public_key = $line")
        parseSshPublicKeyLine(line)?.let {
            appendLine("  type = ${it.algorithm}, blob = byte[${it.signature.size}]")
        } ?: appendLine("  <unparseable authorized-keys line>")
    }
}

private fun StringBuilder.appendSignature(result: Intent) {
    val signature = result.getByteArrayExtra(SshAuthenticationApi.EXTRA_SIGNATURE)
        ?: return
    appendLine("signature = byte[${signature.size}]")
    val frame = parseSshSignatureFrame(signature)
    if (frame == null) {
        appendLine("  <not an RFC 4253 signature blob>")
        appendLine("  ${signature.toHex()}")
        return
    }
    appendLine("  algorithm = ${frame.algorithm}")
    appendLine("  blob = byte[${frame.signature.size}] ${frame.signature.toHex()}")
}
