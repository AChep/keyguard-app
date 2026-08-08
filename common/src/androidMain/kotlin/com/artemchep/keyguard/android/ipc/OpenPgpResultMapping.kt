// The OpenPGP contract needs one mapper per result surface, which adds
// up to more small functions than the detekt threshold allows.
@file:Suppress("TooManyFunctions")

package com.artemchep.keyguard.android.ipc

import android.app.PendingIntent
import android.content.Intent
import android.webkit.MimeTypeMap
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpLiteralFormat
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpLiteralMetadata
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpOperationKind
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.OPENPGP_HEX_RADIX
import com.artemchep.keyguard.common.service.crypto.fingerprintToKeyId
import com.artemchep.keyguard.common.service.crypto.normalized
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_operation_openpgp_autocrypt_status
import com.artemchep.keyguard.res.ipc_operation_openpgp_check_permission
import com.artemchep.keyguard.res.ipc_operation_openpgp_clear_sign
import com.artemchep.keyguard.res.ipc_operation_openpgp_decrypt_metadata
import com.artemchep.keyguard.res.ipc_operation_openpgp_decrypt_verify
import com.artemchep.keyguard.res.ipc_operation_openpgp_detached_sign
import com.artemchep.keyguard.res.ipc_operation_openpgp_encrypt
import com.artemchep.keyguard.res.ipc_operation_openpgp_get_key
import com.artemchep.keyguard.res.ipc_operation_openpgp_get_key_ids
import com.artemchep.keyguard.res.ipc_operation_openpgp_get_sign_key
import com.artemchep.keyguard.res.ipc_operation_openpgp_other
import com.artemchep.keyguard.res.ipc_operation_openpgp_sign_and_encrypt
import org.jetbrains.compose.resources.StringResource
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import java.nio.charset.Charset
import java.util.Date

private const val OPENPGP_LEGACY_RESULT_MAX_API_VERSION = 7
private const val MILLIS_PER_SECOND = 1000L

internal fun normalizeArmorCharset(value: String): String? {
    val candidate = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= 64 }
        ?: return null
    return runCatching {
        Charset
            .forName(candidate)
            .name()
    }.getOrNull()
}

@Suppress("CyclomaticComplexMethod")
internal fun GpgOpenPgpLiteralMetadata.toOpenPgpMetadata(
    armorCharset: String? = null,
): OpenPgpMetadata {
    val normalized = normalized()
    val filename = normalized.fileName
    val extension = filename
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf(String::isNotEmpty)
    val literalMime = when (normalized.format) {
        GpgOpenPgpLiteralFormat.TEXT,
        GpgOpenPgpLiteralFormat.UTF8,
        -> "text/plain"

        GpgOpenPgpLiteralFormat.MIME -> "message/rfc822"
        else -> null
    }
    val mimeType = extension
        ?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
        ?: literalMime
        ?: "application/octet-stream"
    val charset = armorCharset
        ?.let(::normalizeArmorCharset)
        ?: when (normalized.format) {
            GpgOpenPgpLiteralFormat.UTF8 -> "UTF-8"
            else -> null
        }
    return OpenPgpMetadata(
        filename,
        mimeType,
        normalized.modificationTimeEpochSeconds * MILLIS_PER_SECOND,
        normalized.originalSize,
        charset,
    )
}

internal fun GpgOpenPgpVerification?.toApiResult(): OpenPgpSignatureResult = when {
    this == null -> OpenPgpSignatureResult.createWithNoSignature()
    status == GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY ->
        OpenPgpSignatureResult.createWithKeyMissing(
            runCatching { keyId.toULong(OPENPGP_HEX_RADIX).toLong() }.getOrDefault(0L),
            createdAt?.let { Date(it.toEpochMilliseconds()) },
        )

    GpgOpenPgpVerificationWarning.KEY_REVOKED in warnings ->
        createKnownKeySignatureResult(
            result = OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
        )

    GpgOpenPgpVerificationWarning.KEY_EXPIRED in warnings ||
            GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED in warnings ->
        createKnownKeySignatureResult(
            result = OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
        )

    status == GpgOpenPgpVerificationStatus.INVALID ->
        OpenPgpSignatureResult.createWithInvalidSignature()

    else -> createKnownKeySignatureResult(
        result = OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
    )
}

private fun GpgOpenPgpVerification.createKnownKeySignatureResult(
    result: Int,
): OpenPgpSignatureResult = OpenPgpSignatureResult.createWithValidSignature(
        result,
        userIds.firstOrNull(),
        fingerprint
            ?.let(::fingerprintToKeyId)
            ?: runCatching {
                keyId.toULong(OPENPGP_HEX_RADIX).toLong()
            }.getOrDefault(0L),
        userIds,
        emptyList(),
        null,
        createdAt?.let { Date(it.toEpochMilliseconds()) },
    )

internal fun Intent.putOpenPgpVerificationResults(
    apiVersion: Int,
    encrypted: Boolean,
    verification: GpgOpenPgpVerification?,
    metadata: OpenPgpMetadata?,
) {
    val compatibility = openPgpCompatibilityResults(
        apiVersion = apiVersion,
        encrypted = encrypted,
        verification = verification,
    )
    compatibility.decryptionResult?.let { result ->
        putExtra(
            OpenPgpApi.RESULT_DECRYPTION,
            OpenPgpDecryptionResult(result),
        )
    }
    compatibility.signature?.let {
        putExtra(OpenPgpApi.RESULT_SIGNATURE, it)
    }
    metadata?.let {
        putExtra(OpenPgpApi.RESULT_METADATA, it)
        it.charset?.let { charset ->
            putExtra(OpenPgpApi.RESULT_CHARSET, charset)
        }
    }
}

internal data class OpenPgpCompatibilityResults(
    val decryptionResult: Int?,
    val signature: OpenPgpSignatureResult?,
)

internal fun openPgpCompatibilityResults(
    apiVersion: Int,
    encrypted: Boolean,
    verification: GpgOpenPgpVerification?,
): OpenPgpCompatibilityResults {
    val signature = verification.toApiResult()
    if (apiVersion > OPENPGP_LEGACY_RESULT_MAX_API_VERSION) {
        return OpenPgpCompatibilityResults(
            decryptionResult = if (encrypted) {
                OpenPgpDecryptionResult.RESULT_ENCRYPTED
            } else {
                OpenPgpDecryptionResult.RESULT_NOT_ENCRYPTED
            },
            signature = signature,
        )
    }
    @Suppress("DEPRECATION")
    return OpenPgpCompatibilityResults(
        decryptionResult = null,
        signature = signature
            .takeUnless {
                it.result == OpenPgpSignatureResult.RESULT_NO_SIGNATURE
            }
            ?.withSignatureOnlyFlag(!encrypted),
    )
}

/**
 * Maps the OpenPGP API action strings, including their legacy synonyms,
 * onto the platform-neutral operation kinds of the shared GPG policy.
 * Callers only reach this after the action passed the request shape
 * validation, so an unknown action is a programming error.
 */
internal fun openPgpOperationKind(action: String): GpgOpenPgpOperationKind = when (action) {
    OpenPgpApi.ACTION_CHECK_PERMISSION -> GpgOpenPgpOperationKind.CHECK_PERMISSION
    OpenPgpApi.ACTION_SIGN,
    OpenPgpApi.ACTION_CLEARTEXT_SIGN,
    -> GpgOpenPgpOperationKind.CLEAR_SIGN

    OpenPgpApi.ACTION_DETACHED_SIGN -> GpgOpenPgpOperationKind.DETACHED_SIGN
    OpenPgpApi.ACTION_ENCRYPT -> GpgOpenPgpOperationKind.ENCRYPT
    OpenPgpApi.ACTION_SIGN_AND_ENCRYPT -> GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT
    OpenPgpApi.ACTION_DECRYPT_VERIFY -> GpgOpenPgpOperationKind.DECRYPT_VERIFY
    OpenPgpApi.ACTION_DECRYPT_METADATA -> GpgOpenPgpOperationKind.DECRYPT_METADATA
    OpenPgpApi.ACTION_GET_SIGN_KEY_ID,
    OpenPgpApi.ACTION_GET_SIGN_KEY_ID_LEGACY,
    -> GpgOpenPgpOperationKind.GET_SIGN_KEY_ID

    OpenPgpApi.ACTION_GET_KEY_IDS -> GpgOpenPgpOperationKind.GET_KEY_IDS
    OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS -> GpgOpenPgpOperationKind.AUTOCRYPT_STATUS
    OpenPgpApi.ACTION_GET_KEY -> GpgOpenPgpOperationKind.GET_KEY
    else -> error("Unsupported OpenPGP action.")
}

internal fun openPgpOperationName(action: String): StringResource = when (action) {
    OpenPgpApi.ACTION_CHECK_PERMISSION -> Res.string.ipc_operation_openpgp_check_permission
    OpenPgpApi.ACTION_SIGN,
    OpenPgpApi.ACTION_CLEARTEXT_SIGN,
    -> Res.string.ipc_operation_openpgp_clear_sign

    OpenPgpApi.ACTION_DETACHED_SIGN -> Res.string.ipc_operation_openpgp_detached_sign
    OpenPgpApi.ACTION_ENCRYPT -> Res.string.ipc_operation_openpgp_encrypt
    OpenPgpApi.ACTION_SIGN_AND_ENCRYPT -> Res.string.ipc_operation_openpgp_sign_and_encrypt
    OpenPgpApi.ACTION_DECRYPT_VERIFY -> Res.string.ipc_operation_openpgp_decrypt_verify
    OpenPgpApi.ACTION_DECRYPT_METADATA -> Res.string.ipc_operation_openpgp_decrypt_metadata
    OpenPgpApi.ACTION_GET_SIGN_KEY_ID,
    OpenPgpApi.ACTION_GET_SIGN_KEY_ID_LEGACY,
    -> Res.string.ipc_operation_openpgp_get_sign_key

    OpenPgpApi.ACTION_GET_KEY_IDS -> Res.string.ipc_operation_openpgp_get_key_ids
    OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS -> Res.string.ipc_operation_openpgp_autocrypt_status
    OpenPgpApi.ACTION_GET_KEY -> Res.string.ipc_operation_openpgp_get_key
    else -> Res.string.ipc_operation_openpgp_other
}

internal fun openPgpSuccess(
    extras: Intent.() -> Unit = {},
): Intent = Intent().apply {
    putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_SUCCESS)
    extras()
}

internal fun openPgpInteractionRequired(
    pendingIntent: PendingIntent,
): Intent = Intent().apply {
    putExtra(
        OpenPgpApi.RESULT_CODE,
        OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED,
    )
    putExtra(OpenPgpApi.RESULT_INTENT, pendingIntent)
}

internal fun openPgpAutocryptResult(
    status: Int,
    pendingIntent: PendingIntent? = null,
): Intent = openPgpSuccess {
    putExtra(OpenPgpApi.RESULT_AUTOCRYPT_STATUS, status)
    putExtra(OpenPgpApi.RESULT_KEYS_CONFIRMED, false)
    pendingIntent?.let {
        putExtra(OpenPgpApi.RESULT_INTENT, it)
    }
}

internal fun openPgpError(
    errorId: Int,
    message: String,
): Intent = Intent().apply {
    putExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)
    putExtra(
        OpenPgpApi.RESULT_ERROR,
        OpenPgpError(errorId, message),
    )
}
