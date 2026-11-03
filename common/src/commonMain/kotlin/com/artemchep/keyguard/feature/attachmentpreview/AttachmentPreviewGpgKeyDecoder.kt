package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.containsGpgPrivateKeyArmor
import com.artemchep.keyguard.common.service.gpgagent.chunkedGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.gpgkey.createGpgKeyCapabilitiesRow
import com.artemchep.keyguard.feature.gpgkey.createGpgKeyDateRows
import com.artemchep.keyguard.feature.gpgkey.createGpgKeyIdRows
import com.artemchep.keyguard.feature.gpgkey.createGpgKeyStatusInfoItem
import com.artemchep.keyguard.feature.gpgkey.createGpgSubKeyItems
import com.artemchep.keyguard.feature.gpgkey.displayUserIds
import com.artemchep.keyguard.feature.gpgkey.formatGpgAlgorithm
import com.artemchep.keyguard.feature.gpgkey.primaryUserId
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Previews OpenPGP keys and keyrings, both binary and
 * ASCII-armored. Secret keys need no passphrase: only
 * their public part is read.
 */
internal class AttachmentPreviewGpgKeyDecoder(
    private val parser: GpgPublicKeyParser,
    private val dateFormatter: DateFormatter,
    private val translator: TranslatorScope,
    private val clock: Clock = Clock.System,
) {
    suspend fun decode(
        fileName: String,
        bytes: ByteArray,
        copyText: CopyText,
    ): AttachmentPreviewContent {
        val text = if (bytes.isBinaryOpenPgp()) {
            null
        } else {
            decodeTextPreview(
                fileName = fileName,
                bytes = bytes,
                copyText = copyText,
            )
        }
        val result = parser.parse(bytes) as? GpgPublicKeyParseResult.Success
        if (result == null || result.keys.isEmpty()) {
            // Armored files that are not keys, such as
            // signatures, are still worth reading as text.
            return text
                ?: AttachmentPreviewContent.Error(AttachmentPreviewError.GpgKey)
        }

        val source = text as? AttachmentPreviewContent.Text
        val secret = if (source != null) {
            source.text.containsGpgPrivateKeyArmor()
        } else {
            bytes.hasOpenPgpSecretKeyPacket()
        }
        val items = translator.createGpgKeyPreviewItems(
            result = result,
            secret = secret,
            now = clock.now(),
        )
        return AttachmentPreviewContent.GpgKey(
            items = items,
            source = source?.code,
            onCopy = source?.onCopy,
        )
    }

    private suspend fun TranslatorScope.createGpgKeyPreviewItems(
        result: GpgPublicKeyParseResult.Success,
        secret: Boolean,
        now: Instant,
    ): List<VaultViewItem> = buildList {
        if (result.skippedCertificates > 0) {
            this += VaultViewItem.Info(
                id = "gpg.skipped",
                name = translate(
                    Res.plurals.attachment_preview_gpg_skipped_certificates_plural,
                    result.skippedCertificates,
                    result.skippedCertificates,
                ),
            )
        }
        val header = result.keys.size > 1
        result.keys.forEachIndexed { index, key ->
            this += createGpgCertificateItems(
                key = key,
                idPrefix = "gpg.$index",
                secret = secret,
                header = header,
                now = now,
            )
        }
    }

    private suspend fun TranslatorScope.createGpgCertificateItems(
        key: GpgPublicKeyInfo,
        idPrefix: String,
        secret: Boolean,
        header: Boolean,
        now: Instant,
    ): List<VaultViewItem> = buildList {
        val userIds = key.displayUserIds()
        val primaryUserId = key.primaryUserId()
        if (header) {
            val title = primaryUserId
                ?: userIds.firstOrNull()
                ?: key.fingerprint.normalizeGpgFingerprint().chunkedGpgFingerprint()
            this += VaultViewItem.Section(
                id = "$idPrefix.section",
                text = title,
            )
        }
        createGpgKeyStatusInfoItem(
            key = key,
            now = now,
            idPrefix = "$idPrefix.info",
        )?.let { this += it }
        this += VaultViewItem.Table(
            id = "$idPrefix.details",
            elevation = 1.dp,
            rows = createGpgKeyDetailRows(
                key = key,
                secret = secret,
            ),
        )
        this += createGpgSubKeyItems(
            subKeys = key.subKeys,
            now = now,
            dateFormatter = dateFormatter,
            idPrefix = idPrefix,
        )
        this += createGpgUserIdItems(
            userIds = userIds,
            primaryUserId = primaryUserId,
            idPrefix = idPrefix,
        )
    }

    private suspend fun TranslatorScope.createGpgKeyDetailRows(
        key: GpgPublicKeyInfo,
        secret: Boolean,
    ): List<VaultViewItem.Table.Row> = buildList {
        this += createGpgKeyIdRows(
            fingerprint = key.fingerprint,
            keyId = key.keyId,
            keygrip = key.keygrip,
        )
        key.formatGpgAlgorithm()?.let { algorithm ->
            this += VaultViewItem.Table.Row(
                title = translate(Res.string.algorithm),
                value = algorithm,
            )
        }
        this += createGpgKeyDateRows(
            key = key,
            dateFormatter = dateFormatter,
        )
        createGpgKeyCapabilitiesRow(
            canSign = key.canSign,
            canEncrypt = key.canEncrypt,
        )?.let { this += it }
        this += VaultViewItem.Table.Row(
            title = translate(Res.string.gpg_key_sec_pub),
            value = if (secret) "sec" else "pub",
        )
    }

    private suspend fun TranslatorScope.createGpgUserIdItems(
        userIds: List<String>,
        primaryUserId: String?,
        idPrefix: String,
    ): List<VaultViewItem> = buildList {
        if (userIds.isEmpty()) {
            return@buildList
        }
        this += VaultViewItem.Section(
            id = "$idPrefix.userIds.section",
            text = translate(Res.string.gpg_key_user_ids),
        )
        val primaryLabel = translate(Res.string.gpg_user_ids_primary)
        userIds.forEachIndexed { index, userId ->
            this += VaultViewItem.Value(
                id = "$idPrefix.userId.$index",
                elevation = 1.dp,
                title = null,
                value = userId,
                label = primaryLabel.takeIf { userId == primaryUserId },
                maxLines = 4,
            )
        }
    }
}

/**
 * Binary OpenPGP data starts with a packet header, which
 * always has the high bit set. Armor is plain text.
 */
internal fun ByteArray.isBinaryOpenPgp(): Boolean {
    val first = firstOrNull()
        ?.toInt()
        ?: return false
    return first and OPENPGP_PACKET_HEADER_BIT != 0 && !hasUtf8Bom()
}

/**
 * Returns `true` if the first packet is a Secret-Key packet. Supports
 * both the new and the legacy packet header formats.
 */
internal fun ByteArray.hasOpenPgpSecretKeyPacket(): Boolean {
    val header = firstOrNull()
        ?.toInt()
        ?: return false
    val tag = if (header and OPENPGP_NEW_FORMAT_BIT != 0) {
        header and OPENPGP_NEW_FORMAT_TAG_MASK
    } else {
        (header shr 2) and OPENPGP_LEGACY_FORMAT_TAG_MASK
    }
    return tag == OPENPGP_SECRET_KEY_PACKET_TAG
}

private const val OPENPGP_PACKET_HEADER_BIT = 0x80

private const val OPENPGP_NEW_FORMAT_BIT = 0x40

private const val OPENPGP_NEW_FORMAT_TAG_MASK = 0x3F

private const val OPENPGP_LEGACY_FORMAT_TAG_MASK = 0x0F

private const val OPENPGP_SECRET_KEY_PACKET_TAG = 5
