package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_error_already_linked
import com.artemchep.keyguard.res.app_link_pairing_error_cancelled
import com.artemchep.keyguard.res.app_link_pairing_error_expired
import com.artemchep.keyguard.res.app_link_pairing_error_failed
import com.artemchep.keyguard.res.app_link_pairing_error_invalid_code
import com.artemchep.keyguard.res.app_link_pairing_error_linked_together
import com.artemchep.keyguard.res.app_link_pairing_error_mismatch
import com.artemchep.keyguard.res.app_link_pairing_error_network
import com.artemchep.keyguard.res.app_link_pairing_error_peer_update_required
import com.artemchep.keyguard.res.app_link_pairing_error_rejected
import com.artemchep.keyguard.res.app_link_pairing_error_too_large
import com.artemchep.keyguard.res.app_link_pairing_error_update_required
import com.artemchep.keyguard.res.app_link_pairing_error_wrong_kind
import com.artemchep.keyguard.res.app_link_pairing_failed_title
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The text that explains why a pairing failed. */
val KeyguardPairingError.textRes: StringResource
    get() = when (this) {
        KeyguardPairingError.EXPIRED -> Res.string.app_link_pairing_error_expired
        KeyguardPairingError.REJECTED -> Res.string.app_link_pairing_error_rejected
        KeyguardPairingError.MISMATCH -> Res.string.app_link_pairing_error_mismatch
        KeyguardPairingError.UPDATE_REQUIRED -> Res.string.app_link_pairing_error_update_required
        KeyguardPairingError.PEER_UPDATE_REQUIRED -> Res.string.app_link_pairing_error_peer_update_required
        KeyguardPairingError.NETWORK -> Res.string.app_link_pairing_error_network
        KeyguardPairingError.CANCELLED -> Res.string.app_link_pairing_error_cancelled
        KeyguardPairingError.INVALID_CODE -> Res.string.app_link_pairing_error_invalid_code
        KeyguardPairingError.WRONG_KIND -> Res.string.app_link_pairing_error_wrong_kind
        KeyguardPairingError.ALREADY_LINKED -> Res.string.app_link_pairing_error_already_linked
        KeyguardPairingError.LINKED_TOGETHER -> Res.string.app_link_pairing_error_linked_together
        KeyguardPairingError.TOO_LARGE -> Res.string.app_link_pairing_error_too_large
        KeyguardPairingError.FAILED -> Res.string.app_link_pairing_error_failed
    }

/**
 * Why a pairing failed, as the passkey screens say it: a warning, a title, what went
 * wrong, and the way on.
 *
 * @param message what went wrong, from the cause; the text of the [error] if `null`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingErrorPanel(
    error: KeyguardPairingError,
    message: String?,
    actionText: String,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
    ) {
        Icon(
            modifier = Modifier
                .size(48.dp),
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = stringResource(Res.string.app_link_pairing_failed_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = message ?: stringResource(error.textRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onAction != null) {
            Spacer(Modifier.height(16.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = onAction,
                ) {
                    Text(
                        text = actionText,
                    )
                }
            }
        }
    }
}
