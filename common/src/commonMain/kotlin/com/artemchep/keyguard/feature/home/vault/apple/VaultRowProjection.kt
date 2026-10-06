package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.resolveWebsiteIconUrl
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.home.vault.screen.richFields
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField


/** Maps a cipher type name to its SF Symbol fallback icon. */
internal fun appleCipherTypeSymbol(typeName: String?): String = when (typeName) {
    "Login" -> "person.badge.key"
    "Card" -> "creditcard"
    "Identity" -> "person.text.rectangle"
    "SecureNote" -> "note.text"
    "SshKey" -> "terminal"
    else -> "key"
}

/** Maps a matched search field to its SF Symbol context badge. */
fun appleVaultTextFieldSymbol(field: VaultTextField): String = when (field) {
    VaultTextField.Title -> "textformat"
    VaultTextField.Url -> "link"
    VaultTextField.Host -> "globe"
    VaultTextField.Username -> "at"
    VaultTextField.Email -> "envelope"
    VaultTextField.PasskeyRpId -> "person.badge.key"
    VaultTextField.IdentityName -> "person"
    VaultTextField.Phone -> "phone"
    VaultTextField.CardholderName -> "creditcard"
    VaultTextField.CardBrand -> "creditcard"
    VaultTextField.PasskeyDisplayName -> "person.badge.key"
    VaultTextField.AttachmentName -> "paperclip"
    VaultTextField.FieldName -> "info.circle"
    VaultTextField.Note -> "note.text"
    VaultTextField.Field -> "info.circle"
    VaultTextField.Ssh -> "terminal"
    VaultTextField.Gpg -> "key"
    VaultTextField.Password -> "key"
    VaultTextField.CardNumber -> "creditcard"
}

internal fun applePasskeyTapKind(mode: AppMode): Int = when (mode) {
    is AppMode.PickPasskey -> AppleVaultRowBadge.TAP_COMPLETE_PICK
    else -> AppleVaultRowBadge.TAP_OPEN_PASSKEY
}

internal fun VaultItem2.Item.toAppleRowContent(
    rev: Long,
    passkeyTapKind: Int,
    extraFlags: Int = 0,
): AppleVaultRowContent {
    val rich = richFields()
    var flags = extraFlags
    if (favourite) flags = flags or AppleVaultRowContent.FLAG_FAVOURITE
    if (rich.reprompt) flags = flags or AppleVaultRowContent.FLAG_REPROMPT
    if (rich.hasAttachments) flags = flags or AppleVaultRowContent.FLAG_ATTACHMENTS
    if (rich.hasError) flags = flags or AppleVaultRowContent.FLAG_ERROR
    if (rich.hasTotp) flags = flags or AppleVaultRowContent.FLAG_HAS_TOTP
    if (rich.isMultiline) flags = flags or AppleVaultRowContent.FLAG_MULTILINE
    if (action is VaultItem2.Item.Action.Go) flags = flags or AppleVaultRowContent.FLAG_CHEVRON

    val badges = ArrayList<AppleVaultRowBadge>(
        rich.passwordBadges.size + rich.passkeyBadges.size + rich.attachmentBadges.size,
    )
    val passwordSources = passwords.filter { it.source.password != null }
    rich.passwordBadges.forEachIndexed { index, badge ->
        val source = passwordSources.getOrNull(index)
        badges += AppleVaultRowBadge(
            id = "password.$index",
            kind = AppleVaultRowBadge.KIND_PASSWORD,
            text = badge.title,
            text2 = badge.text.orEmpty(),
            tapKind = if (source?.onClick != null) {
                AppleVaultRowBadge.TAP_LARGE_TYPE
            } else {
                AppleVaultRowBadge.TAP_NONE
            },
        )
    }
    // Rich passkey / attachment badges map 1:1 (same order) onto the item
    // lists, which carry the stable source ids.
    rich.passkeyBadges.forEachIndexed { index, badge ->
        val credentialId = passkeys.getOrNull(index)?.source?.credentialId.orEmpty()
        badges += AppleVaultRowBadge(
            id = "passkey.$credentialId",
            kind = AppleVaultRowBadge.KIND_PASSKEY,
            text = badge.title,
            text2 = badge.text.orEmpty(),
            tapKind = passkeyTapKind,
        )
    }
    rich.attachmentBadges.forEachIndexed { index, badge ->
        val attachmentId = attachments2.getOrNull(index)?.source?.id.orEmpty()
        badges += AppleVaultRowBadge(
            id = "attachment.$attachmentId",
            kind = AppleVaultRowBadge.KIND_ATTACHMENT,
            text = badge.title,
            text2 = badge.text.orEmpty(),
            // The canonical badge tap action for attachments is a no-op,
            // see `buildVaultBadgeTapActions`.
            tapKind = AppleVaultRowBadge.TAP_NONE,
        )
    }

    val org = rich.organizationName
    return AppleVaultRowContent(
        id = id,
        rev = rev,
        kind = AppleVaultEntry.KIND_ITEM,
        secretId = source.id,
        accountId = accountId,
        title = title.text,
        subtitle = text.orEmpty(),
        flags = flags,
        typeSymbol = appleCipherTypeSymbol(rich.typeName),
        iconUrl = icon.resolveWebsiteIconUrl().orEmpty(),
        iconInitials = VaultItemIcon.TextIcon.short(title.text).text,
        accentLightArgb = rich.accentArgbLight ?: 0,
        accentDarkArgb = rich.accentArgbDark ?: 0,
        orgName = org.orEmpty(),
        orgAccentLightArgb = rich.organizationAccentArgbLight ?: 0,
        orgAccentDarkArgb = rich.organizationAccentArgbDark ?: 0,
        badges = badges,
        shapeState = shapeState,
    )
}

/** A section row: translated label only. */
internal fun appleSectionRowContent(
    id: String,
    title: String,
): AppleVaultRowContent = AppleVaultRowContent(
    id = id,
    rev = 0L.mixRevValue(title.hashCode().toLong()),
    kind = AppleVaultEntry.KIND_SECTION,
    secretId = "",
    accountId = "",
    title = title,
    subtitle = "",
    flags = 0,
    typeSymbol = "",
    iconUrl = "",
    iconInitials = "",
    accentLightArgb = 0,
    accentDarkArgb = 0,
    orgName = "",
    orgAccentLightArgb = 0,
    orgAccentDarkArgb = 0,
    badges = emptyList(),
    shapeState = 0,
)

internal fun appleButtonRowContent(
    id: String,
    title: String,
): AppleVaultRowContent = AppleVaultRowContent(
    id = id,
    rev = 0L.mixRevValue(title.hashCode().toLong()),
    kind = AppleVaultEntry.KIND_BUTTON,
    secretId = "",
    accountId = "",
    title = title,
    subtitle = "",
    flags = 0,
    typeSymbol = "",
    iconUrl = "",
    iconInitials = "",
    accentLightArgb = 0,
    accentDarkArgb = 0,
    orgName = "",
    orgAccentLightArgb = 0,
    orgAccentDarkArgb = 0,
    badges = emptyList(),
    shapeState = 0,
)

/** A content-less marker row (no-items / no-suggestions / quick-filters). */
internal fun appleMarkerRowContent(
    id: String,
    kind: Int,
): AppleVaultRowContent = AppleVaultRowContent(
    id = id,
    rev = 0L,
    kind = kind,
    secretId = "",
    accountId = "",
    title = "",
    subtitle = "",
    flags = 0,
    typeSymbol = "",
    iconUrl = "",
    iconInitials = "",
    accentLightArgb = 0,
    accentDarkArgb = 0,
    orgName = "",
    orgAccentLightArgb = 0,
    orgAccentDarkArgb = 0,
    badges = emptyList(),
    shapeState = 0,
)

internal fun appleRowContentRev(
    fingerprint: Long,
    trimmedItem: VaultItem2.Item,
    selectionFlags: Int = 0,
): Long = fingerprint
    .mixRevValue(if (trimmedItem.token != null) 1L else 0L)
    .mixRevValue(trimmedItem.passwords.size.toLong())
    .mixRevValue(trimmedItem.passkeys.size.toLong())
    .mixRevValue(trimmedItem.attachments2.size.toLong())
    .mixRevValue(trimmedItem.shapeState.toLong())
    .let { if (selectionFlags != 0) it.mixRevValue(selectionFlags.toLong()) else it }

// Local FNV-1a fold; plain hash math (the canonical fingerprint mixers in
// VaultRowCache.kt are private, and this derived rev is a Apple-only concept).
private const val REV_FNV64_PRIME = 0x100000001b3L

private fun Long.mixRevValue(value: Long): Long {
    var hash = this
    var v = value
    repeat(Long.SIZE_BYTES) {
        hash = (hash xor (v and BYTE_MASK)) * REV_FNV64_PRIME
        v = v ushr Byte.SIZE_BITS
    }
    return hash
}

private const val BYTE_MASK = 0xFFL
