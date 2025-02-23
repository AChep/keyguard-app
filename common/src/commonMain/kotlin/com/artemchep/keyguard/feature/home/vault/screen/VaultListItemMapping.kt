package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.login
import com.artemchep.keyguard.common.model.password
import com.artemchep.keyguard.common.model.passwordRevisionDate
import com.artemchep.keyguard.common.model.passwordStrength
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.home.vault.component.VaultViewTotpBadge
import com.artemchep.keyguard.feature.home.vault.component.obscureCardNumber
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.concealedText
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.StateFlow

suspend fun DSecret.toVaultListItem(
    copy: CopyText,
    translator: TranslatorScope,
    getTotpCode: GetTotpCode,
    appIcons: Boolean,
    websiteIcons: Boolean,
    concealFields: Boolean,
    groupId: String? = null,
    organizationsById: Map<String, DOrganization>,
    onClick: (List<FlatItemAction>) -> VaultItem2.Item.Action,
    onClickAttachment: suspend (DSecret.Attachment) -> (() -> Unit)?,
    onClickPasskey: suspend (DSecret.Login.Fido2Credentials) -> (() -> Unit)?,
    localStateFlow: StateFlow<VaultItem2.Item.LocalState>,
): VaultItem2.Item {
    val cf = concealFields || reprompt
    val d = when (type) {
        DSecret.Type.Login ->
            createLogin(
                copy = copy,
                translator = translator,
                getTotpCode = getTotpCode,
                concealFields = cf,
            )

        DSecret.Type.Card ->
            createCard(
                copy = copy,
                translator = translator,
                concealFields = cf,
            )

        DSecret.Type.Identity ->
            createIdentity(
                copy = copy,
                translator = translator,
                concealFields = cf,
            )

        DSecret.Type.SecureNote ->
            createNote(
                copy = copy,
                translator = translator,
                concealFields = cf,
            )

        DSecret.Type.SshKey ->
            createSshKey(
                copy = copy,
                translator = translator,
                concealFields = cf,
            )

        else -> createUnknown()
    }

    val icon = toVaultItemIcon(
        appIcons = appIcons,
        websiteIcons = websiteIcons,
    )
    return VaultItem2.Item(
        id = id,
        source = this,
        accentLight = accentLight,
        accentDark = accentDark,
        accountId = accountId,
        groupId = groupId,
        revisionDate = revisionDate,
        createdDate = createdDate,
        feature = when {
            organizationId != null -> {
                val org = organizationsById[organizationId]
                if (org != null) {
                    VaultItem2.Item.Feature.Organization(
                        name = org.name,
                        accentColors = org.accentColor,
                    )
                } else {
                    VaultItem2.Item.Feature.None
                }
            }

            login?.totp?.token != null -> {
                val token = login.totp.token
                VaultItem2.Item.Feature.Totp(token)
            }

            else -> VaultItem2.Item.Feature.None
        },
        copyText = copy,
        token = login?.totp?.token,
        passkeys = login?.fido2Credentials.orEmpty()
            .mapNotNull {
                val onClick = onClickPasskey(it)
                    ?: return@mapNotNull null
                VaultItem2.Item.Passkey(
                    source = it,
                    onClick = onClick,
                )
            }
            .toImmutableList(),
        attachments2 = attachments
            .mapNotNull {
                val onClick = onClickAttachment(it)
                    ?: return@mapNotNull null
                VaultItem2.Item.Attachment(
                    source = it,
                    onClick = onClick,
                )
            }
            .toImmutableList(),
        password = DSecret.login.password.getOrNull(this),
        passwordRevisionDate = DSecret.login.passwordRevisionDate.getOrNull(this),
        score = DSecret.login.passwordStrength.getOrNull(this),
        icon = icon,
        type = type.name,
        folderId = folderId,
        favourite = favorite,
        attachments = attachments.isNotEmpty(),
        title = AnnotatedString(name.trim()),
        text = d.text.trim(),
        action = onClick(d.actions),
        localStateFlow = localStateFlow,
    )
}

fun DSecret.toVaultItemIcon(
    appIcons: Boolean,
    websiteIcons: Boolean,
): VaultItemIcon = kotlin.run {
    val vectorIconSrc = type.iconImageVector()
    val cardIcon = run {
        val cardIcon = card
            ?.creditCardType
            ?.icon
        if (cardIcon != null) {
            VaultItemIcon.ImageIcon(
                imageRes = cardIcon,
            )
        } else {
            null
        }
    }
    val textIcon = if (name.isNotBlank()) {
        VaultItemIcon.TextIcon.short(name)
    } else {
        null
    }
    val vectorIcon = VaultItemIcon.VectorIcon(
        imageVector = vectorIconSrc,
    )
    val appIcon = if (appIcons) {
        uris
            .firstOrNull { uri -> uri.uri.startsWith(PROTOCOL_ANDROID_APP) }
            ?.let { uri ->
                val packageName = uri.uri.substringAfter(PROTOCOL_ANDROID_APP)
                VaultItemIcon.AppIcon(
                    data = AppIconUrl(packageName),
                    fallback = vectorIcon,
                )
            }
    } else {
        null
    }
    val websiteIcon = if (websiteIcons) {
        favicon
            ?.let { url ->
                VaultItemIcon.WebsiteIcon(
                    data = url,
                    fallback = appIcon ?: vectorIcon,
                )
            }
    } else {
        null
    }
    websiteIcon ?: appIcon ?: cardIcon ?: textIcon ?: vectorIcon
}

private data class TypeSpecific(
    val text: String,
    val actions: List<FlatItemAction> = emptyList(),
)

private suspend fun DSecret.createLogin(
    copy: CopyText,
    translator: TranslatorScope,
    getTotpCode: GetTotpCode,
    concealFields: Boolean,
): TypeSpecific {
    val actions = listOfNotNull(
        copy.FlatItemAction(
            title = Res.string.copy_username.wrap(),
            value = login?.username,
        ),
        copy.FlatItemAction(
            title = Res.string.copy_password.wrap(),
            value = login?.password,
            hidden = concealFields,
        ),
        login?.totp?.run {
            FlatItemAction(
                icon = Icons.Outlined.ContentCopy,
                title = Res.string.copy_otp_code.wrap(),
                trailing = {
                    Row {
                        VaultViewTotpBadge(
                            totpToken = login.totp.token,
                        )
                    }
                },
                onClick = {
                    getTotpCode(token)
                        .toIO()
                        .effectTap { code ->
                            copy.copy(
                                text = code.code,
                                hidden = false,
                                type = CopyText.Type.OTP,
                            )
                        }
                        .attempt()
                        .launchIn(GlobalScope)
                },
            )
        },
    )
    return TypeSpecific(
        text = login?.username
            ?: login?.fido2Credentials
                ?.firstNotNullOfOrNull { it.userDisplayName }
            ?: "",
        actions = actions,
    )
}

private fun DSecret.createCard(
    copy: CopyText,
    translator: TranslatorScope,
    concealFields: Boolean,
): TypeSpecific {
    val actions = listOfNotNull(
        copy.FlatItemAction(
            title = Res.string.copy_card_number.wrap(),
            value = card?.number,
            hidden = concealFields,
        ),
        copy.FlatItemAction(
            title = Res.string.copy_cvv_code.wrap(),
            value = card?.code,
            hidden = concealFields,
        ),
    )
    val text = kotlin.run {
        val textRaw = card?.number.orEmpty()
        obscureCardNumber(textRaw)
    }
    return TypeSpecific(
        text = text,
        actions = actions,
    )
}

private fun DSecret.createIdentity(
    copy: CopyText,
    translator: TranslatorScope,
    concealFields: Boolean,
): TypeSpecific {
    val actions = listOfNotNull(
        copy.FlatItemAction(
            title = Res.string.copy_phone_number.wrap(),
            value = identity?.phone,
        ),
        copy.FlatItemAction(
            title = Res.string.copy_email.wrap(),
            value = identity?.email,
        ),
        copy.FlatItemAction(
            title = Res.string.copy_passport_number.wrap(),
            value = identity?.passportNumber,
            hidden = concealFields,
        ),
        copy.FlatItemAction(
            title = Res.string.copy_license_number.wrap(),
            value = identity?.licenseNumber,
            hidden = concealFields,
        ),
    )
    val text = identity?.firstName.orEmpty()
    return TypeSpecific(
        text = text,
        actions = actions,
    )
}

private fun DSecret.createNote(
    copy: CopyText,
    translator: TranslatorScope,
    concealFields: Boolean,
): TypeSpecific {
    val text = if (reprompt) {
        concealedText()
    } else {
        notes
    }
    return TypeSpecific(
        text = text,
    )
}

private fun DSecret.createSshKey(
    copy: CopyText,
    translator: TranslatorScope,
    concealFields: Boolean,
): TypeSpecific {
    val text = sshKey?.fingerprint.orEmpty()
    return TypeSpecific(
        text = text,
    )
}

private fun DSecret.createUnknown(): TypeSpecific {
    return TypeSpecific(
        text = "",
    )
}
