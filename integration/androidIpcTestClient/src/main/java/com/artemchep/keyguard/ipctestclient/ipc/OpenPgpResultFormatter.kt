package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi

/**
 * Decodes every result extra the OpenPGP API defines, so an unexpected response
 * is readable without attaching a debugger.
 */
@Suppress("DEPRECATION")
fun formatOpenPgpResult(result: Intent, output: ByteArray?): String = buildString {
    val code = result.getIntExtra(OpenPgpApi.RESULT_CODE, IpcExchange.UNKNOWN_RESULT_CODE)
    appendLine("result_code = ${openPgpResultCodeName(code)}")
    result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)?.let {
        appendLine("error = ${openPgpErrorName(it.errorId)}: ${it.message}")
    }
    if (result.hasExtra(OpenPgpApi.RESULT_INTENT)) {
        appendLine("intent = PendingIntent (user interaction)")
    }
    appendKeyResults(result)
    appendVerificationResults(result)
    appendAutocryptResults(result)
    output?.let { appendLine("output = byte[${it.size}]\n${it.preview()}") }
}

private fun StringBuilder.appendKeyResults(result: Intent) {
    if (result.hasExtra(OpenPgpApi.RESULT_SIGN_KEY_ID)) {
        val keyId = result.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L)
        appendLine("sign_key_id = ${keyId.toKeyIdHex()}")
    }
    result.getStringExtra(OpenPgpApi.RESULT_PRIMARY_USER_ID)?.let {
        appendLine("primary_user_id = $it")
    }
    if (result.hasExtra(OpenPgpApi.RESULT_KEY_CREATION_TIME)) {
        val createdAt = result.getLongExtra(OpenPgpApi.RESULT_KEY_CREATION_TIME, 0L)
        appendLine("key_creation_time = $createdAt ms (${java.util.Date(createdAt)})")
    }
    result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)?.let { keyIds ->
        appendLine("key_ids = ${keyIds.joinToString { it.toKeyIdHex() }}")
    }
    result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)?.let {
        appendLine("detached_signature = byte[${it.size}]\n${it.preview()}")
    }
    result.getStringExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG)?.let {
        appendLine("signature_micalg = $it")
    }
}

@Suppress("DEPRECATION")
private fun StringBuilder.appendVerificationResults(result: Intent) {
    result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)?.let {
        appendLine("signature = ${openPgpSignatureStatusName(it.result)}")
        appendLine("  key_id = ${it.keyId.toKeyIdHex()}")
        appendLine("  primary_user_id = ${it.primaryUserId}")
        appendLine("  user_ids = ${it.userIds}")
        appendLine("  confirmed_user_ids = ${it.confirmedUserIds}")
        appendLine("  sender_status = ${it.senderStatusResult}")
        appendLine("  timestamp = ${it.signatureTimestamp}")
    }
    result.getParcelableExtra<OpenPgpDecryptionResult>(OpenPgpApi.RESULT_DECRYPTION)?.let {
        appendLine("decryption = ${openPgpDecryptionStatusName(it.result)}")
        appendLine("  has_decrypted_session_key = ${it.hasDecryptedSessionKey()}")
    }
    result.getParcelableExtra<OpenPgpMetadata>(OpenPgpApi.RESULT_METADATA)?.let {
        appendLine("metadata:")
        appendLine("  filename = ${it.filename}")
        appendLine("  mime_type = ${it.mimeType}")
        appendLine("  modification_time = ${it.modificationTime}")
        appendLine("  original_size = ${it.originalSize}")
        appendLine("  charset = ${it.charset}")
    }
    result.getStringExtra(OpenPgpApi.RESULT_CHARSET)?.let {
        appendLine("charset = $it")
    }
}

private fun StringBuilder.appendAutocryptResults(result: Intent) {
    if (result.hasExtra(OpenPgpApi.RESULT_AUTOCRYPT_STATUS)) {
        val status = result.getIntExtra(
            OpenPgpApi.RESULT_AUTOCRYPT_STATUS,
            IpcExchange.UNKNOWN_RESULT_CODE,
        )
        appendLine("autocrypt_status = ${openPgpAutocryptStatusName(status)}")
    }
    if (result.hasExtra(OpenPgpApi.RESULT_KEYS_CONFIRMED)) {
        val confirmed = result.getBooleanExtra(OpenPgpApi.RESULT_KEYS_CONFIRMED, false)
        appendLine("keys_confirmed = $confirmed")
    }
}
