package com.artemchep.keyguard.feature.passkeys

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.info
import com.artemchep.keyguard.res.passkey_discoverable
import com.artemchep.keyguard.res.passkey_relying_party
import com.artemchep.keyguard.res.passkey_signature_counter
import com.artemchep.keyguard.res.passkey_user_display_name
import com.artemchep.keyguard.res.passkey_user_username
import org.jetbrains.compose.resources.StringResource

@Immutable
sealed interface PasskeysCredentialViewItem {
    val key: String

    @Immutable
    data class Text(
        override val key: String,
        val icon: ImageVector,
        val title: StringResource,
        val value: String?,
    ) : PasskeysCredentialViewItem

    @Immutable
    data class Section(
        override val key: String,
        val title: StringResource,
    ) : PasskeysCredentialViewItem

    @Immutable
    data class RelyingParty(
        override val key: String,
        val icon: ImageVector,
        val rpName: String?,
        val rpId: String,
    ) : PasskeysCredentialViewItem

    @Immutable
    data class SignatureCounter(
        override val key: String,
        val icon: ImageVector,
        val counter: Int?,
    ) : PasskeysCredentialViewItem

    @Immutable
    data class Discoverable(
        override val key: String,
        val icon: ImageVector,
        val value: Boolean,
    ) : PasskeysCredentialViewItem

    @Immutable
    data class CreatedAt(
        override val key: String,
        val text: String,
    ) : PasskeysCredentialViewItem
}

fun PasskeysCredentialViewState.Content.passkeysCredentialViewItems(): List<PasskeysCredentialViewItem> = listOf(
    PasskeysCredentialViewItem.Text(
        key = "user_display_name",
        icon = Icons.Outlined.AccountCircle,
        title = Res.string.passkey_user_display_name,
        value = model.userDisplayName,
    ),
    PasskeysCredentialViewItem.Text(
        key = "username",
        icon = Icons.Outlined.AlternateEmail,
        title = Res.string.passkey_user_username,
        value = model.userName,
    ),
    PasskeysCredentialViewItem.Section(
        key = "info_header",
        title = Res.string.info,
    ),
    PasskeysCredentialViewItem.RelyingParty(
        key = "relying_party",
        icon = Icons.Outlined.Business,
        rpName = model.rpName,
        rpId = model.rpId,
    ),
    PasskeysCredentialViewItem.SignatureCounter(
        key = "signature_counter",
        icon = Icons.Outlined.Key,
        counter = model.counter,
    ),
    PasskeysCredentialViewItem.Discoverable(
        key = "discoverable",
        icon = Icons.Outlined.Public,
        value = model.discoverable,
    ),
    PasskeysCredentialViewItem.CreatedAt(
        key = "created_at",
        text = createdAt,
    ),
)

fun PasskeysCredentialViewItem.infoUrlOrNull(): String? = when (this) {
    is PasskeysCredentialViewItem.Discoverable -> "https://www.w3.org/TR/webauthn-2/#discoverable-credential"
    is PasskeysCredentialViewItem.RelyingParty -> "https://www.w3.org/TR/webauthn-2/#relying-party"
    is PasskeysCredentialViewItem.SignatureCounter -> "https://www.w3.org/TR/webauthn-2/#signature-counter"
    else -> null
}
