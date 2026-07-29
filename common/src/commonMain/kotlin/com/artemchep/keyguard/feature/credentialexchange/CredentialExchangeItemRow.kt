package com.artemchep.keyguard.feature.credentialexchange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One item row on a review screen: its title plus a badge per credential kind.
 *
 * Shared by the export and import screens so an item looks the same whichever way it
 * is travelling.
 */
@Composable
fun CredentialExchangeItemRow(
    item: CredentialExchangeItem,
    selected: Boolean? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
) {
    val updatedOnSelectedChange by rememberUpdatedState(onSelectedChange)
    FlatItemLayoutExpressive(
        shapeState = item.shapeState,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    CredentialKindBadges(
                        credentials = item.credentials,
                    )
                },
            )
        },
        trailing = selected?.let { checked ->
            // composable
            {
                Checkbox(
                    checked = checked,
                    enabled = updatedOnSelectedChange != null,
                    onCheckedChange = { value ->
                        updatedOnSelectedChange?.invoke(value)
                    },
                )
            }
        },
        onClick = selected?.let { checked ->
            onSelectedChange?.let {
                {
                    updatedOnSelectedChange?.invoke(!checked)
                }
            }
        },
        // A claimed import disables only the selection affordance; the item itself
        // remains readable at normal emphasis while the commit is running.
        enabled = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialKindBadges(
    credentials: List<CredentialExchangeItem.Kind>,
) {
    FlowRow(
        modifier = Modifier
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        credentials.forEach { kind ->
            CredentialKindBadge(
                kind = kind,
            )
        }
    }
}

private data class CredentialKindVisuals(
    val icon: ImageVector,
    val label: StringResource,
)

private fun CredentialExchangeItem.Kind.visuals(): CredentialKindVisuals = when (this) {
    CredentialExchangeItem.Kind.Passkey -> CredentialKindVisuals(
        icon = Icons.Outlined.KeyguardPasskey,
        label = Res.string.credential_exchange_export_credential_passkey,
    )

    CredentialExchangeItem.Kind.Password -> CredentialKindVisuals(
        icon = Icons.Outlined.Password,
        label = Res.string.credential_exchange_export_credential_password,
    )

    CredentialExchangeItem.Kind.Totp -> CredentialKindVisuals(
        icon = Icons.Outlined.KeyguardTwoFa,
        label = Res.string.credential_exchange_export_credential_totp,
    )

    CredentialExchangeItem.Kind.Card -> CredentialKindVisuals(
        icon = Icons.Outlined.CreditCard,
        label = Res.string.credential_exchange_export_credential_card,
    )

    CredentialExchangeItem.Kind.Identity -> CredentialKindVisuals(
        icon = Icons.Outlined.Person,
        label = Res.string.credential_exchange_export_credential_identity,
    )

    CredentialExchangeItem.Kind.Note -> CredentialKindVisuals(
        icon = Icons.Outlined.KeyguardNote,
        label = Res.string.credential_exchange_export_credential_note,
    )

    CredentialExchangeItem.Kind.Fields -> CredentialKindVisuals(
        icon = Icons.Outlined.Tune,
        label = Res.string.credential_exchange_export_credential_fields,
    )

    CredentialExchangeItem.Kind.SshKey -> CredentialKindVisuals(
        icon = Icons.Outlined.KeyguardSshKey,
        label = Res.string.credential_exchange_export_credential_ssh_key,
    )
}

@Composable
private fun CredentialKindBadge(
    kind: CredentialExchangeItem.Kind,
) {
    val visuals = kind.visuals()
    val icon = visuals.icon
    val label = stringResource(visuals.label)
    Surface(
        shape = RoundedCornerShape(BADGE_CORNER_RADIUS_DP.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier
                    .size(BADGE_ICON_SIZE_DP.dp),
                imageVector = icon,
                contentDescription = null,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val BADGE_CORNER_RADIUS_DP = 8
private const val BADGE_ICON_SIZE_DP = 16
