package com.artemchep.keyguard.feature.gpgkey

import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.crypto.hasAuthenticatedMetadata
import com.artemchep.keyguard.common.service.crypto.isExpiredAt
import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import com.artemchep.keyguard.common.service.gpgagent.chunkedGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.time.Instant

/**
 * Returns the trimmed, distinct user IDs, or the emails
 * if the key has no user IDs.
 */
internal fun GpgPublicKeyInfo.displayUserIds(): List<String> = userIds
    .ifEmpty { emails }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

internal fun GpgPublicKeyInfo.primaryUserId(): String? = userIdDetails
    .firstOrNull { it.primary }
    ?.userId
    ?.trim()

/**
 * Returns the most important warning about the [key]'s status,
 * or `null` if the key is fine.
 */
internal suspend fun TranslatorScope.createGpgKeyStatusInfoItem(
    key: GpgPublicKeyInfo,
    now: Instant,
    idPrefix: String,
): VaultViewItem.Info? {
    // There is no live policy evaluation, so both self-signature
    // states are read off the parse result. `authenticated == false` alone does
    // not say which one it is: a weak-hash key that a renewal repairs and a key
    // whose self-signature is missing, invalid or policy-rejected are both
    // unauthenticated. The renewal tier is what separates them, so the
    // remediation advice matches.
    val weakSelfSignature = key.renewal == GpgRenewalAuthorization.TEMPLATE_ONLY
    val unauthenticatedSelfSignature = !key.hasAuthenticatedMetadata
    return when {
        key.revoked -> VaultViewItem.Info(
            id = "$idPrefix.revoked",
            name = translate(Res.string.revoked),
            message = translate(Res.string.gpg_key_status_revoked_text),
        )

        key.isExpiredAt(now) -> VaultViewItem.Info(
            id = "$idPrefix.expired",
            name = translate(Res.string.expired),
            message = translate(Res.string.gpg_key_status_expired_text),
        )

        weakSelfSignature -> VaultViewItem.Info(
            id = "$idPrefix.weakSelfSignature",
            name = translate(Res.string.gpg_key_status_weak_self_signature_title),
            message = translateGpgWeakSelfSignature(key.weakSelfSignatureAlgorithms),
        )

        unauthenticatedSelfSignature -> VaultViewItem.Info(
            id = "$idPrefix.unauthenticatedSelfSignature",
            name = translate(Res.string.gpg_key_status_unauthenticated_self_signature_title),
            message = translate(Res.string.gpg_key_status_unauthenticated_self_signature_text),
        )

        else -> null
    }
}

/**
 * Returns a section with a table per subkey, or
 * nothing if no subkey has details to show.
 */
internal suspend fun TranslatorScope.createGpgSubKeyItems(
    subKeys: List<GpgPublicSubKeyInfo>,
    now: Instant,
    dateFormatter: DateFormatter,
    idPrefix: String,
): List<VaultViewItem> {
    val tables = subKeys
        .mapIndexedNotNull { index, subKey ->
            val rows = createGpgSubKeyRows(
                subKey = subKey,
                now = now,
                dateFormatter = dateFormatter,
            )
            if (rows.isEmpty()) {
                return@mapIndexedNotNull null
            }
            VaultViewItem.Table(
                id = "$idPrefix.subKey.$index",
                elevation = 1.dp,
                title = translate(Res.string.gpg_key_subkey_n, index + 1),
                rows = rows,
            )
        }
    if (tables.isEmpty()) {
        return emptyList()
    }
    val section = VaultViewItem.Section(
        id = "$idPrefix.subKeys.section",
        text = translate(Res.string.subkeys),
    )
    return listOf(section) + tables
}

private suspend fun TranslatorScope.createGpgSubKeyRows(
    subKey: GpgPublicSubKeyInfo,
    now: Instant,
    dateFormatter: DateFormatter,
): List<VaultViewItem.Table.Row> = buildList {
    this += createGpgKeyIdRows(
        fingerprint = subKey.fingerprint,
        keyId = subKey.keyId,
        keygrip = subKey.keygrip,
    )
    subKey.formatGpgAlgorithm()?.let { algorithm ->
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.algorithm),
            value = algorithm,
        )
    }
    subKey.createdAt?.let { createdAt ->
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.created),
            value = dateFormatter.formatDate(createdAt),
        )
    }
    subKey.expiresAt?.let { expiresAt ->
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.expires),
            value = dateFormatter.formatDate(expiresAt),
        )
    }
    createGpgKeyCapabilitiesRow(
        canSign = subKey.canSign,
        canEncrypt = subKey.canEncrypt,
    )?.let { this += it }
    val status = when {
        subKey.revoked -> Res.string.revoked
        subKey.isExpiredAt(now) -> Res.string.expired
        else -> null
    }
    if (status != null) {
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.gpg_key_status),
            value = translate(status),
        )
    }
}

/**
 * Returns the fingerprint, key ID and keygrip rows. The key ID
 * is omitted when it is the fingerprint.
 */
internal suspend fun TranslatorScope.createGpgKeyIdRows(
    fingerprint: String,
    keyId: String,
    keygrip: String?,
): List<VaultViewItem.Table.Row> = buildList {
    val normalizedFingerprint = fingerprint
        .normalizeGpgFingerprint()
        .takeIf { it.isNotEmpty() }
    if (normalizedFingerprint != null) {
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.fingerprint),
            value = normalizedFingerprint.chunkedGpgFingerprint(),
        )
    }
    val normalizedKeyId = keyId
        .normalizeGpgFingerprint()
        .takeIf { it.isNotEmpty() }
    if (normalizedKeyId != null && normalizedKeyId != normalizedFingerprint) {
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.key_id),
            value = normalizedKeyId.chunkedGpgFingerprint(),
        )
    }
    // Not chunked: the keygrip should match the output of
    // gpg --with-keygrip.
    keygrip
        ?.normalizeGpgKeygrip()
        ?.takeIf { it.isNotEmpty() }
        ?.let { normalizedKeygrip ->
            this += VaultViewItem.Table.Row(
                title = translate(Res.string.keygrip),
                value = normalizedKeygrip,
            )
        }
}

/**
 * Returns the created and expires rows. Only authenticated
 * metadata may claim that the key does not expire.
 */
internal suspend fun TranslatorScope.createGpgKeyDateRows(
    key: GpgPublicKeyInfo,
    dateFormatter: DateFormatter,
): List<VaultViewItem.Table.Row> = buildList {
    key.createdAt?.let { createdAt ->
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.created),
            value = dateFormatter.formatDate(createdAt),
        )
    }
    val noExpiry = if (key.hasAuthenticatedMetadata) {
        Res.string.gpg_key_does_not_expire
    } else {
        Res.string.gpg_key_expiry_unknown
    }
    this += VaultViewItem.Table.Row(
        title = translate(Res.string.expires),
        value = key.expiresAt
            ?.let(dateFormatter::formatDate)
            ?: translate(noExpiry),
    )
}

internal suspend fun TranslatorScope.createGpgKeyCapabilitiesRow(
    canSign: Boolean,
    canEncrypt: Boolean,
): VaultViewItem.Table.Row? {
    val capabilities = buildList {
        if (canSign) {
            this += translate(Res.string.gpg_keys_capability_sign)
        }
        if (canEncrypt) {
            this += translate(Res.string.gpg_key_capability_encrypt_decrypt)
        }
    }
    if (capabilities.isEmpty()) {
        return null
    }
    return VaultViewItem.Table.Row(
        title = translate(Res.string.capabilities),
        value = capabilities.joinToString(),
    )
}

internal fun GpgPublicKeyInfo.formatGpgAlgorithm(): String? = formatGpgAlgorithm(
    algorithm = algorithm,
    bitStrength = bitStrength,
)

internal fun GpgPublicSubKeyInfo.formatGpgAlgorithm(): String? = formatGpgAlgorithm(
    algorithm = algorithm,
    bitStrength = bitStrength,
)

private fun formatGpgAlgorithm(
    algorithm: String,
    bitStrength: Int?,
): String? {
    val values = listOfNotNull(
        algorithm.takeIf { it.isNotBlank() },
        bitStrength?.let { "$it-bit" },
    )
    return values
        .joinToString(separator = " ")
        .takeIf { it.isNotBlank() }
}
