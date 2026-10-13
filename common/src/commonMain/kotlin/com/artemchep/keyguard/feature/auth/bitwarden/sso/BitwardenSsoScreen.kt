package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal expect fun SsoBrowserEffect(prompt: SsoBrowserPrompt)

@Composable
fun BitwardenSsoScreen(
    args: BitwardenSsoRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val state = produceBitwardenSsoScreenState(
        args = args,
        transmitter = transmitter,
    )
    BitwardenSsoScreenContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitwardenSsoScreenContent(
    state: BitwardenSsoState,
) {
    state.browser?.let { SsoBrowserEffect(it) }
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.sso_login),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            // The browser step has no primary action.
            val fabState = when {
                state.password != null -> FabState(
                    onClick = state.onUnlock,
                    model = null,
                )

                state.onRetry != null -> FabState(
                    onClick = state.onRetry,
                    model = null,
                )

                else -> null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            SsoFab(
                state = state,
            )
        },
        overlay = {
            DefaultProgressBar(
                visible = state.isLoading,
            )
        },
    ) {
        SsoContent(
            state = state,
        )
    }
}

@Composable
private fun FabScope.SsoFab(
    state: BitwardenSsoState,
) {
    if (state.password != null) {
        SsoFab(
            icon = Icons.Outlined.LockOpen,
            text = Res.string.sso_unlock,
            isLoading = state.isLoading,
        )
    } else {
        SsoFab(
            icon = Icons.Outlined.Refresh,
            text = Res.string.retry,
            isLoading = false,
        )
    }
}

@Composable
private fun FabScope.SsoFab(
    icon: ImageVector,
    text: StringResource,
    isLoading: Boolean,
) {
    DefaultFab(
        icon = {
            Crossfade(
                modifier = Modifier
                    .size(24.dp),
                targetState = isLoading,
            ) { isLoading ->
                if (isLoading) {
                    KeyguardLoadingIndicator()
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                    )
                }
            }
        },
        text = {
            Text(
                text = stringResource(text),
            )
        },
    )
}

@Composable
private fun ColumnScope.SsoContent(
    state: BitwardenSsoState,
) {
    val updatedOnUnlock by rememberUpdatedState(state.onUnlock)
    val note = if (state.password != null) {
        Res.string.sso_unlock_note
    } else {
        Res.string.sso_browser_note
    }
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(note),
        style = MaterialTheme.typography.bodyMedium,
    )
    state.password?.let { field ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }
        Spacer(Modifier.height(16.dp))
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding)
                .focusRequester(focusRequester),
            fieldModifier = Modifier
                .contentType(ContentType.Password),
            value = field,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    updatedOnUnlock?.invoke()
                },
            ),
        )
    }
}
