package com.artemchep.keyguard.wear.feature

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.LoginContent
import com.artemchep.keyguard.feature.auth.bitwarden.produceBitwardenLoginScreenState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_header_title
import com.artemchep.keyguard.res.addaccount_sign_in_button
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearManualLoginScreen(
    args: BitwardenLoginRoute.Args,
    onSuccess: () -> Unit,
    onTwoFactor: (BitwardenLoginTwofaRoute.Args) -> Unit,
) {
    val loadableState = produceBitwardenLoginScreenState(args = args)
    when (loadableState) {
        Loadable.Loading -> WearLoadingScreen()
        is Loadable.Ok -> {
            val state = loadableState.value
            LaunchedEffect(state) {
                state.effects.onSuccessFlow.collect {
                    onSuccess()
                }
            }
            LaunchedEffect(state) {
                state.effects.onErrorFlow.collect { error ->
                    when (error) {
                        is BitwardenLoginEvent.Error.OtpRequired -> onTwoFactor(error.args)
                    }
                }
            }
            WearScaffoldColumn(
                title = stringResource(Res.string.addaccount_header_title),
                floatingActionState = run {
                    val fabOnClick = state.onLoginClick
                    val fabState = FabState(
                        onClick = fabOnClick,
                        model = null,
                    )
                    rememberUpdatedState(newValue = fabState)
                },
                floatingActionButton = {
                    DefaultFab(
                        icon = {
                            Crossfade(
                                modifier = Modifier
                                    .size(16.dp),
                                targetState = state.isLoading,
                            ) { isLoading ->
                                if (isLoading) {
                                    KeyguardLoadingIndicator()
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Login,
                                        contentDescription = null,
                                    )
                                }
                            }
                        },
                        text = {
                            Text(
                                text = stringResource(Res.string.addaccount_sign_in_button),
                            )
                        },
                    )
                },
            ) {
                LoginContent(state)
            }
        }
    }
}