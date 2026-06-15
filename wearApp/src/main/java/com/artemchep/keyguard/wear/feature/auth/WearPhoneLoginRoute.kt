package com.artemchep.keyguard.wear.feature.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_phone_title
import com.artemchep.keyguard.res.retry
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource

data class WearPhoneLoginRoute(
    val accountType: AccountType,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        WearPhoneLoginScreen(
            accountType = accountType,
            transmitter = transmitter,
        )
    }
}

@Composable
private fun WearPhoneLoginScreen(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val state = wearPhoneLoginScreenState(
        accountType = accountType,
        transmitter = transmitter,
    )

    WearScaffoldScreen(
        title = stringResource(Res.string.addaccount_method_phone_title),
        overlay = {
            WearScaffoldLoader(
                modifier = Modifier,
                visible = state.showProgress,
            )
        },
        floatingActionState = run {
            val onAction = state.onActionClick
            val state = if (onAction != null) {
                FabState(
                    onClick = state.onActionClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(state)
        },
        floatingActionButton = {
            DefaultEdgeButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                    )
                },
                text = {
                    state.actionText?.let { actionText ->
                        Text(text = textResource(actionText))
                    }
                },
            )
        },
    ) { transformationSpec ->
        item("status") {
            WearListLabel(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                text = textResource(state.statusText),
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
}
