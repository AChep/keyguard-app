package com.artemchep.keyguard.android.ipc

import android.content.Intent
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpOperationKind
import com.artemchep.keyguard.common.service.crypto.normalizeGpgMailboxAddress
import com.artemchep.keyguard.common.service.crypto.normalizeGpgUserIdEmail
import org.openintents.openpgp.util.OpenPgpApi

internal const val MIN_API_VERSION = 7
internal const val MAX_API_VERSION = 12

private const val MAX_OPENPGP_FILENAME_LENGTH = 255
private const val MAX_OPENPGP_USER_ID_LENGTH = 320
private const val MAX_DETACHED_SIGNATURE_BYTES = 1024 * 1024
private const val MAX_OPENPGP_KEY_COUNT = 64

internal class NormalizedOpenPgpRequest(
    val apiVersion: Int,
    val retryIntent: Intent,
    val extras: NormalizedOpenPgpExtras,
)

internal class NormalizedOpenPgpExtras(
    val digestParts: List<String>,
    val asciiArmor: Boolean,
    val compression: Boolean,
    val opportunistic: Boolean,
    val originalFilename: String?,
    /** Canonical mailboxes derived from recipient or signing-identity API extras. */
    val requestedEmails: List<String>,
    val keyIds: LongArray,
    val signKeyId: Long?,
    val preselectKeyId: Long?,
    val keyId: Long?,
    val senderAddress: String?,
    val detachedSignature: ByteArray?,
) {
    /** Explicit key constraints that may narrow the approval chooser. */
    val approvalConstraintKeyIds: List<Long>
        get() = keyIds.toList() + listOfNotNull(keyId, signKeyId)
}

@Suppress("LongMethod")
internal fun normalizeRequest(
    request: Intent,
    action: String,
    kind: GpgOpenPgpOperationKind,
    apiVersion: Int,
): NormalizedOpenPgpRequest? = runCatching {
    val userIds = request.getStringArrayExtra(OpenPgpApi.EXTRA_USER_IDS)
    val userId = request
        .getStringExtra(OpenPgpApi.EXTRA_USER_ID)
        .takeIf { userIds == null }
    val isSignKeyQuery = kind == GpgOpenPgpOperationKind.GET_SIGN_KEY_ID
    val extras = normalizeOpenPgpExtras(
        apiVersion = apiVersion,
        hasCustomHeaders = request.hasExtra(OpenPgpApi.EXTRA_CUSTOM_HEADERS),
        minimize = request.getBooleanExtra(OpenPgpApi.EXTRA_MINIMIZE, false),
        asciiArmor = request.getBooleanExtra(
            OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR,
            false,
        ),
        compression = request.getBooleanExtra(
            OpenPgpApi.EXTRA_ENABLE_COMPRESSION,
            true,
        ),
        opportunistic = request.getBooleanExtra(
            OpenPgpApi.EXTRA_OPPORTUNISTIC_ENCRYPTION,
            false,
        ),
        originalFilename = request.getStringExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME),
        userIds = userIds ?: userId?.let { arrayOf(it) },
        allowUserIdSyntax = userId != null && isSignKeyQuery,
        keyIds = request.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS),
        selectedKeyIds = request.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS_SELECTED),
        signKeyId = request.getLongExtraOrNull(OpenPgpApi.EXTRA_SIGN_KEY_ID),
        // The hint only steers the sign key chooser, so other actions drop it
        // at admission and downstream code never has to check the kind again.
        preselectKeyId = request
            .getLongExtraOrNull(OpenPgpApi.EXTRA_PRESELECT_KEY_ID)
            ?.takeIf { isSignKeyQuery },
        keyId = request.getLongExtraOrNull(OpenPgpApi.EXTRA_KEY_ID),
        senderAddress = request.getStringExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS),
        detachedSignature = request.getByteArrayExtra(
            OpenPgpApi.EXTRA_DETACHED_SIGNATURE,
        ),
    ) ?: return null
    val retryIntent = Intent(action).apply {
        putExtra(OpenPgpApi.EXTRA_API_VERSION, apiVersion)
        putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, extras.asciiArmor)
        putExtra(OpenPgpApi.EXTRA_ENABLE_COMPRESSION, extras.compression)
        putExtra(OpenPgpApi.EXTRA_OPPORTUNISTIC_ENCRYPTION, extras.opportunistic)
        extras.originalFilename?.let {
            putExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME, it)
        }
        if (extras.requestedEmails.isNotEmpty()) {
            putExtra(OpenPgpApi.EXTRA_USER_IDS, extras.requestedEmails.toTypedArray())
        }
        if (extras.keyIds.isNotEmpty()) {
            putExtra(OpenPgpApi.EXTRA_KEY_IDS, extras.keyIds)
        }
        extras.signKeyId?.let { putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, it) }
        extras.preselectKeyId?.let {
            putExtra(OpenPgpApi.EXTRA_PRESELECT_KEY_ID, it)
        }
        extras.keyId?.let { putExtra(OpenPgpApi.EXTRA_KEY_ID, it) }
        extras.senderAddress?.let {
            putExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS, it)
        }
        extras.detachedSignature?.let {
            putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, it)
        }
    }
    NormalizedOpenPgpRequest(
        apiVersion = apiVersion,
        retryIntent = retryIntent,
        extras = extras,
    )
}.getOrNull()

private fun Intent.getLongExtraOrNull(name: String): Long? =
    if (hasExtra(name)) getLongExtra(name, 0L) else null

@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
)
internal fun normalizeOpenPgpExtras(
    apiVersion: Int,
    hasCustomHeaders: Boolean = false,
    minimize: Boolean = false,
    asciiArmor: Boolean = false,
    compression: Boolean = true,
    opportunistic: Boolean = false,
    originalFilename: String? = null,
    userIds: Array<String>? = null,
    allowUserIdSyntax: Boolean = false,
    keyIds: LongArray? = null,
    selectedKeyIds: LongArray? = null,
    signKeyId: Long? = null,
    preselectKeyId: Long? = null,
    keyId: Long? = null,
    senderAddress: String? = null,
    detachedSignature: ByteArray? = null,
): NormalizedOpenPgpExtras? {
    if (
        hasCustomHeaders ||
        minimize ||
        originalFilename?.length?.let { it > MAX_OPENPGP_FILENAME_LENGTH } == true
    ) {
        return null
    }
    val requestedUserIds = userIds
        .orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .takeIf {
            it.size <= 64 && it.all { value -> value.length <= MAX_OPENPGP_USER_ID_LENGTH }
        }
        ?: return null
    val normalizeRequestedEmail: (String) -> String? = if (allowUserIdSyntax) {
        ::normalizeGpgUserIdEmail
    } else {
        ::normalizeGpgMailboxAddress
    }
    val requestedEmails = requestedUserIds.map { normalizeRequestedEmail(it) ?: return null }
    val normalizedKeyIds = (keyIds ?: longArrayOf())
        .takeIf { it.size <= MAX_OPENPGP_KEY_COUNT }
        ?: return null
    val normalizedSelectedKeyIds = (selectedKeyIds ?: longArrayOf())
        .takeIf { it.size <= MAX_OPENPGP_KEY_COUNT }
        ?: return null
    if (
        detachedSignature != null &&
        (
            detachedSignature.isEmpty() ||
                    detachedSignature.size > MAX_DETACHED_SIGNATURE_BYTES
            )
    ) {
        return null
    }
    val normalizedDetachedSignature = detachedSignature?.copyOf()
    // The retry intent carries the merged set under EXTRA_KEY_IDS alone, so
    // the cap must hold for the union or the retried request would fail the
    // per-array bound right after the user approved it.
    val combinedKeyIds = (
        normalizedKeyIds.asSequence() +
                normalizedSelectedKeyIds.asSequence()
        )
        .distinct()
        .toList()
        .takeIf { it.size <= MAX_OPENPGP_KEY_COUNT }
        ?.toLongArray()
        ?: return null
    val normalizedSignKeyId = signKeyId?.takeIf { it != 0L }
    val normalizedPreselectKeyId = preselectKeyId?.takeIf { it != 0L }
    val normalizedExportKeyId = keyId?.takeIf { it != 0L }
    // Keep a supplied but invalid address ("") distinct from an absent address
    // (null). The result contract maps the former to USER_ID_MISSING and the
    // latter to UNKNOWN.
    val normalizedSenderAddress = when {
        senderAddress == null -> null
        senderAddress.length > MAX_OPENPGP_USER_ID_LENGTH -> ""
        else -> normalizeGpgMailboxAddress(senderAddress).orEmpty()
    }
    return NormalizedOpenPgpExtras(
        digestParts = buildList {
            add("version=$apiVersion")
            add("armor=$asciiArmor")
            add("compression=$compression")
            add("opportunistic=$opportunistic")
            add("filename=${originalFilename.orEmpty()}")
            requestedEmails.sorted().forEach { add("user_id=$it") }
            combinedKeyIds.sorted().forEach { add("key_id=$it") }
            add("sign_key_id=${normalizedSignKeyId ?: 0L}")
            add("preselect_key_id=${normalizedPreselectKeyId ?: 0L}")
            add("export_key_id=${normalizedExportKeyId ?: 0L}")
            normalizedSenderAddress?.let { add("sender_address=$it") }
            add("detached_size=${normalizedDetachedSignature?.size ?: 0}")
            normalizedDetachedSignature?.let {
                add("detached_sha256=${androidIpcByteDigest(it)}")
            }
        },
        asciiArmor = asciiArmor,
        compression = compression,
        opportunistic = opportunistic,
        originalFilename = originalFilename,
        requestedEmails = requestedEmails,
        keyIds = combinedKeyIds,
        signKeyId = normalizedSignKeyId,
        preselectKeyId = normalizedPreselectKeyId,
        keyId = normalizedExportKeyId,
        senderAddress = normalizedSenderAddress,
        detachedSignature = normalizedDetachedSignature,
    )
}

internal fun hasValidOpenPgpActionExtras(
    action: String,
    request: NormalizedOpenPgpRequest,
): Boolean = hasValidOpenPgpActionExtras(
    action = action,
    keyId = request.extras.keyId,
    hasDetachedSignature = request.extras.detachedSignature != null,
)

internal fun hasValidOpenPgpActionExtras(
    action: String,
    keyId: Long?,
    hasDetachedSignature: Boolean,
): Boolean {
    if (
        hasDetachedSignature &&
        action != OpenPgpApi.ACTION_DECRYPT_VERIFY &&
        action != OpenPgpApi.ACTION_DECRYPT_METADATA
    ) {
        return false
    }
    return action != OpenPgpApi.ACTION_GET_KEY || keyId != null
}

internal fun isSupportedOpenPgpApiVersion(version: Int): Boolean =
    version in MIN_API_VERSION..MAX_API_VERSION
