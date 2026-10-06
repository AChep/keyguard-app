package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.ui.graphics.toArgb
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.obscurePassword
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2

data class RichVaultItemFields(
    /** The cipher type name (Login / Card / Identity / SecureNote / SshKey / None). */
    val typeName: String?,
    val accentArgbLight: Int?,
    val accentArgbDark: Int?,
    /** Master-password reprompt is enabled → a lock badge on the icon. */
    val reprompt: Boolean,
    /** The cipher has attachments → a paperclip badge on the icon. */
    val hasAttachments: Boolean,
    /** The cipher has a sync / network error → a trailing error indicator. */
    val hasError: Boolean,
    /** Secure notes show a taller subtitle (4 lines vs 2). */
    val isMultiline: Boolean,
    /** The owning organization's name → a trailing accent chip; null for personal items. */
    val organizationName: String?,
    val organizationAccentArgbLight: Int?,
    val organizationAccentArgbDark: Int?,
    /** A TOTP token is present → the row shows the live one-time-password badge. */
    val hasTotp: Boolean,
    /** The raw token, used to drive the per-surface live-TOTP channel. */
    val token: TotpToken?,
    val passwordBadges: List<RichBadge>,
    val passkeyBadges: List<RichBadge>,
    val attachmentBadges: List<RichBadge>,
)

/** One inline badge of a cipher row — an icon plus a title and optional subtitle. */
data class RichBadge(
    val title: String,
    val text: String?,
    /** SF Symbol name the native row renders as the badge's leading icon. */
    val iconName: String,
)

fun VaultItem2.Item.richFields(): RichVaultItemFields {
    val org = feature as? VaultItem2.Item.Feature.Organization
    val passwordBadges = passwords.mapNotNull { pw ->
        val raw = pw.source.password ?: return@mapNotNull null
        val title = if (pw.conceal) obscurePassword(raw) else raw
        RichBadge(title = title, text = null, iconName = "key")
    }
    val passkeyBadges = passkeys.map { pk ->
        RichBadge(
            title = pk.source.userDisplayName.orEmpty(),
            text = pk.source.rpId,
            iconName = "person.badge.key",
        )
    }
    val attachmentBadges = attachments2.map { at ->
        RichBadge(
            title = at.source.fileName(),
            text = at.source.fileSize()?.let(::humanReadableByteCountSI),
            iconName = "paperclip",
        )
    }
    return RichVaultItemFields(
        typeName = source.type.name,
        accentArgbLight = accentLight.toArgb(),
        accentArgbDark = accentDark.toArgb(),
        reprompt = source.reprompt,
        hasAttachments = attachments,
        hasError = source.hasError,
        isMultiline = source.type == DSecret.Type.SecureNote,
        organizationName = org?.name,
        organizationAccentArgbLight = org?.accentColors?.light?.toArgb(),
        organizationAccentArgbDark = org?.accentColors?.dark?.toArgb(),
        hasTotp = token != null,
        token = token,
        passwordBadges = passwordBadges,
        passkeyBadges = passkeyBadges,
        attachmentBadges = attachmentBadges,
    )
}
