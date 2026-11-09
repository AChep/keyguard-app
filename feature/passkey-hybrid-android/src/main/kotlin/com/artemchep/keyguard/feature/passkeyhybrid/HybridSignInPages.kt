package com.artemchep.keyguard.feature.passkeyhybrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.component.launchAppDetailsSettings
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridCandidate
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridError
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridOperation
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridPhase
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSessionState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.done
import com.artemchep.keyguard.res.local_network_permission_open_settings_action
import com.artemchep.keyguard.res.passkey_hybrid_choose
import com.artemchep.keyguard.res.passkey_hybrid_connecting
import com.artemchep.keyguard.res.passkey_hybrid_connecting_text
import com.artemchep.keyguard.res.passkey_hybrid_error_connection
import com.artemchep.keyguard.res.passkey_hybrid_error_connection_title
import com.artemchep.keyguard.res.passkey_hybrid_error_credentials
import com.artemchep.keyguard.res.passkey_hybrid_error_credentials_title
import com.artemchep.keyguard.res.passkey_hybrid_error_excluded
import com.artemchep.keyguard.res.passkey_hybrid_error_excluded_title
import com.artemchep.keyguard.res.passkey_hybrid_error_permission
import com.artemchep.keyguard.res.passkey_hybrid_error_permission_title
import com.artemchep.keyguard.res.passkey_hybrid_error_qr
import com.artemchep.keyguard.res.passkey_hybrid_error_qr_title
import com.artemchep.keyguard.res.passkey_hybrid_error_write
import com.artemchep.keyguard.res.passkey_hybrid_error_write_title
import com.artemchep.keyguard.res.passkey_hybrid_intro_text
import com.artemchep.keyguard.res.passkey_hybrid_intro_title
import com.artemchep.keyguard.res.passkey_hybrid_permission_text
import com.artemchep.keyguard.res.passkey_hybrid_permission_title
import com.artemchep.keyguard.res.passkey_hybrid_relay
import com.artemchep.keyguard.res.passkey_hybrid_requirements
import com.artemchep.keyguard.res.passkey_hybrid_sending
import com.artemchep.keyguard.res.passkey_hybrid_sending_text
import com.artemchep.keyguard.res.passkey_hybrid_step_1_text
import com.artemchep.keyguard.res.passkey_hybrid_step_1_title
import com.artemchep.keyguard.res.passkey_hybrid_step_2_text
import com.artemchep.keyguard.res.passkey_hybrid_step_2_title
import com.artemchep.keyguard.res.passkey_hybrid_step_3_text
import com.artemchep.keyguard.res.passkey_hybrid_step_3_title
import com.artemchep.keyguard.res.passkey_hybrid_success_create
import com.artemchep.keyguard.res.passkey_hybrid_success_sign_in
import com.artemchep.keyguard.res.passkey_hybrid_success_text
import com.artemchep.keyguard.res.passkey_hybrid_waiting
import com.artemchep.keyguard.res.passkey_hybrid_waiting_text
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.icons.ShapedIcon
import com.artemchep.keyguard.ui.icons.ShapedIconSize
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val HybridSteps =
    listOf(
        Res.string.passkey_hybrid_step_1_title to Res.string.passkey_hybrid_step_1_text,
        Res.string.passkey_hybrid_step_2_title to Res.string.passkey_hybrid_step_2_text,
        Res.string.passkey_hybrid_step_3_title to Res.string.passkey_hybrid_step_3_text,
    )

/** One page of the flow inside the wizard. SAVE and VERIFY are hosted outside of it. */
@Composable
internal fun HybridPage(state: HybridSessionState) {
    when (state.phase) {
        HybridPhase.SCAN -> HybridIntroPage()
        HybridPhase.PERMISSION -> HybridPermissionPage()
        HybridPhase.CONNECTING,
        HybridPhase.WAITING,
        HybridPhase.SENDING,
        -> HybridProgressPage(state)

        HybridPhase.PICK -> HybridPickPage(state)
        HybridPhase.SUCCESS -> HybridSuccessPage(state)
        HybridPhase.ERROR -> HybridErrorPage(state)
        HybridPhase.SAVE,
        HybridPhase.VERIFY,
        -> Unit
    }
}

@Composable
private fun HybridIntroPage() {
    HybridStatusCard(
        icon = {
            ShapedIcon(
                icon = Icons.Outlined.KeyguardPasskey,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        title = stringResource(Res.string.passkey_hybrid_intro_title),
        text = stringResource(Res.string.passkey_hybrid_intro_text),
    )
    // The steps must not inherit the page's spacing between them.
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        HybridSteps.forEachIndexed { index, (title, text) ->
            HybridStep(
                number = index + 1,
                title = stringResource(title),
                text = stringResource(text),
            )
        }
    }
    FlatSimpleNote(
        type = SimpleNote.Type.INFO,
        text = stringResource(Res.string.passkey_hybrid_requirements),
    )
    HybridFootnote(
        text = stringResource(Res.string.passkey_hybrid_relay),
    )
}

/** A numbered step, laid out as the steps of the import screen were. */
@Composable
private fun HybridStep(
    number: Int,
    title: String,
    text: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar {
            Text(
                text = number.toString(),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(
                modifier = Modifier
                    .height(2.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
    }
}

@Composable
private fun HybridPermissionPage() {
    HybridStatusCard(
        icon = {
            ShapedIcon(
                icon = Icons.Outlined.Bluetooth,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        title = stringResource(Res.string.passkey_hybrid_permission_title),
        text = stringResource(Res.string.passkey_hybrid_permission_text),
    )
}

@Composable
private fun HybridProgressPage(state: HybridSessionState) {
    val (title, text) =
        when (state.phase) {
            HybridPhase.WAITING -> {
                Res.string.passkey_hybrid_waiting to Res.string.passkey_hybrid_waiting_text
            }

            HybridPhase.SENDING -> {
                Res.string.passkey_hybrid_sending to Res.string.passkey_hybrid_sending_text
            }

            else -> {
                Res.string.passkey_hybrid_connecting to Res.string.passkey_hybrid_connecting_text
            }
        }
    HybridRequestHeader(state)
    HybridStatusCard(
        icon = {
            KeyguardLoadingIndicator(
                modifier = Modifier
                    .size(ShapedIconSize),
                contained = true,
            )
        },
        title = stringResource(title),
        text = stringResource(text),
    )
}

@Composable
private fun HybridPickPage(state: HybridSessionState) {
    HybridRequestHeader(state)
    Column {
        Section(
            text = stringResource(Res.string.passkey_hybrid_choose),
        )
        state.candidates.forEachIndexed { index, candidate ->
            key(candidate.accountId, candidate.cipherId, candidate.credentialId) {
                HybridCandidateItem(
                    candidate = candidate,
                    shapeState = getShapeState(state.candidates, index) { _, _ -> true },
                    onClick = state.onPick?.let { onPick -> { onPick(candidate) } },
                )
            }
        }
    }
}

@Composable
private fun HybridCandidateItem(
    candidate: HybridCandidate,
    shapeState: Int,
    onClick: (() -> Unit)?,
) {
    // A passkey may have no user name; the item's name then leads.
    val username = candidate.username.takeIf { it.isNotBlank() }
    FlatItemLayoutExpressive(
        shapeState = shapeState,
        leading = icon<RowScope>(Icons.Outlined.Key),
        content = {
            FlatItemTextContent(
                title = {
                    Text(username ?: candidate.title)
                },
                text = if (username != null) {
                    // composable
                    {
                        Text(candidate.title)
                    }
                } else {
                    null
                },
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HybridSuccessPage(state: HybridSessionState) {
    val title =
        when (state.operation) {
            HybridOperation.CREATE -> Res.string.passkey_hybrid_success_create
            HybridOperation.SIGN_IN,
            null,
            -> Res.string.passkey_hybrid_success_sign_in
        }
    HybridRequestHeader(state)
    HybridStatusCard(
        icon = {
            ShapedIcon(
                icon = Icons.Outlined.Check,
                containerColor = MaterialTheme.colorScheme.okContainer,
                contentColor = MaterialTheme.colorScheme.onOkContainer,
            )
        },
        title = stringResource(title),
        text = stringResource(Res.string.passkey_hybrid_success_text),
        content = {
            val controller = LocalNavigationController.current
            FilledTonalButton(
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    controller.queue(NavigationIntent.Pop)
                },
            ) {
                Text(
                    text = stringResource(Res.string.done),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HybridErrorPage(state: HybridSessionState) {
    val (title, text) = hybridErrorText(state.error)
    HybridRequestHeader(state)
    HybridStatusCard(
        icon = {
            ShapedIcon(
                icon = Icons.Outlined.ErrorOutline,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        title = stringResource(title),
        text = stringResource(text),
        content = if (state.error == HybridError.PERMISSION) {
            // composable
            {
                val context = LocalContext.current
                // Tonal, so its edge lines up with the text above.
                FilledTonalButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        context.launchAppDetailsSettings()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.local_network_permission_open_settings_action),
                    )
                }
            }
        } else {
            null
        },
    )
}

private fun hybridErrorText(error: HybridError?): Pair<StringResource, StringResource> =
    when (error) {
        HybridError.QR -> {
            Res.string.passkey_hybrid_error_qr_title to Res.string.passkey_hybrid_error_qr
        }

        HybridError.PERMISSION -> {
            Res.string.passkey_hybrid_error_permission_title to Res.string.passkey_hybrid_error_permission
        }

        HybridError.NO_CREDENTIALS -> {
            Res.string.passkey_hybrid_error_credentials_title to Res.string.passkey_hybrid_error_credentials
        }

        HybridError.WRITE -> {
            Res.string.passkey_hybrid_error_write_title to Res.string.passkey_hybrid_error_write
        }

        HybridError.EXCLUDED -> {
            Res.string.passkey_hybrid_error_excluded_title to Res.string.passkey_hybrid_error_excluded
        }

        HybridError.CONNECTION,
        null,
        -> {
            Res.string.passkey_hybrid_error_connection_title to Res.string.passkey_hybrid_error_connection
        }
    }
