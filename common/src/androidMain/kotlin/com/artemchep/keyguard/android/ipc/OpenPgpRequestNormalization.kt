package com.artemchep.keyguard.android.ipc

import android.content.Intent
import com.artemchep.keyguard.common.service.crypto.normalizeGpgUserIdEmail
import org.openintents.openpgp.util.OpenPgpApi

internal const val MIN_API_VERSION = 7
internal const val MAX_API_VERSION = 12

private const val MAX_OPENPGP_FILENAME_LENGTH = 255
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
    val userIds: List<String>,
    val keyIds: LongArray,
    val signKeyId: Long?,
    val keyId: Long?,
    val detachedSignature: ByteArray?,
)

@Suppress("LongMethod")
internal fun normalizeRequest(
    request: Intent,
    action: String,
    apiVersion: Int,
): NormalizedOpenPgpRequest? = runCatching {
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
        userIds = request.getStringArrayExtra(OpenPgpApi.EXTRA_USER_IDS)
            ?: request
                .getStringExtra(OpenPgpApi.EXTRA_USER_ID)
                ?.let { arrayOf(it) },
        keyIds = request.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS),
        selectedKeyIds = request.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS_SELECTED),
        signKeyId = (
            request
                .takeIf { it.hasExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID) }
                ?.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L)
                ?: request
                .takeIf { it.hasExtra(OpenPgpApi.EXTRA_PRESELECT_KEY_ID) }
                ?.getLongExtra(OpenPgpApi.EXTRA_PRESELECT_KEY_ID, 0L)
            ),
        keyId = request
            .takeIf { it.hasExtra(OpenPgpApi.EXTRA_KEY_ID) }
            ?.getLongExtra(OpenPgpApi.EXTRA_KEY_ID, 0L),
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
        if (extras.userIds.isNotEmpty()) {
            putExtra(OpenPgpApi.EXTRA_USER_IDS, extras.userIds.toTypedArray())
        }
        if (extras.keyIds.isNotEmpty()) {
            putExtra(OpenPgpApi.EXTRA_KEY_IDS, extras.keyIds)
        }
        extras.signKeyId?.let { putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, it) }
        extras.keyId?.let { putExtra(OpenPgpApi.EXTRA_KEY_ID, it) }
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
    keyIds: LongArray? = null,
    selectedKeyIds: LongArray? = null,
    signKeyId: Long? = null,
    keyId: Long? = null,
    detachedSignature: ByteArray? = null,
): NormalizedOpenPgpExtras? {
    if (
        hasCustomHeaders ||
        minimize ||
        originalFilename?.length?.let { it > MAX_OPENPGP_FILENAME_LENGTH } == true
    ) {
        return null
    }
    val normalizedUserIds = userIds
        .orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .takeIf {
            it.size <= 64 && it.all { value -> value.length <= 320 }
        }
        ?: return null
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
    val normalizedExportKeyId = keyId?.takeIf { it != 0L }
    return NormalizedOpenPgpExtras(
        digestParts = buildList {
            add("version=$apiVersion")
            add("armor=$asciiArmor")
            add("compression=$compression")
            add("opportunistic=$opportunistic")
            add("filename=${originalFilename.orEmpty()}")
            normalizedUserIds
                .map(::normalizeGpgUserIdEmail)
                .sortedBy { it.orEmpty() }
                .forEach { add("user_id=${it.orEmpty()}") }
            combinedKeyIds.sorted().forEach { add("key_id=$it") }
            add("sign_key_id=${normalizedSignKeyId ?: 0L}")
            add("export_key_id=${normalizedExportKeyId ?: 0L}")
            add("detached_size=${normalizedDetachedSignature?.size ?: 0}")
            normalizedDetachedSignature?.let {
                add("detached_sha256=${androidIpcByteDigest(it)}")
            }
        },
        asciiArmor = asciiArmor,
        compression = compression,
        opportunistic = opportunistic,
        originalFilename = originalFilename,
        userIds = normalizedUserIds,
        keyIds = combinedKeyIds,
        signKeyId = normalizedSignKeyId,
        keyId = normalizedExportKeyId,
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
