package com.artemchep.keyguard.wear.feature.auth

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_header_title
import com.artemchep.keyguard.res.addaccount_method_manual_text
import com.artemchep.keyguard.res.addaccount_method_manual_title
import com.artemchep.keyguard.res.addaccount_method_phone_title
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource

data class WearLoginMethodRoute(
    val accountType: AccountType,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        WearLoginMethodScreen(
            accountType = accountType,
            transmitter = transmitter,
        )
    }
}

@Composable
private fun WearLoginMethodScreen(
    accountType: AccountType,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val state = wearLoginMethodScreenState(
        accountType = accountType,
        transmitter = transmitter,
    )
    WearScaffoldScreen(
        title = stringResource(Res.string.addaccount_method_header_title),
    ) { transformationSpec ->
        state.infoText?.let { infoText ->
            item("info") {
                WearListLabel(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    text = textResource(infoText),
                    textAlign = TextAlign.Start,
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }

        item("phone") {
            val updatedOnPhoneLoginClick by rememberUpdatedState(state.onPhoneLoginClick)
            WearListAction(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                    )
                },
                title = {
                    Text(
                        text = stringResource(Res.string.addaccount_method_phone_title),
                    )
                },
                text = {
                    Text(
                        text = textResource(state.phoneStatusText),
                    )
                },
                onClick = {
                    updatedOnPhoneLoginClick?.invoke()
                },
                enabled = updatedOnPhoneLoginClick != null && state.isPhoneLoginEnabled,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        if (state.isManualLoginVisible) {
            item("manual") {
                val updatedOnManualLoginClick by rememberUpdatedState(state.onManualLoginClick)
                WearListAction(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Watch,
                            contentDescription = null,
                        )
                    },
                    title = {
                        Text(
                            text = stringResource(Res.string.addaccount_method_manual_title),
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(Res.string.addaccount_method_manual_text),
                        )
                    },
                    onClick = {
                        updatedOnManualLoginClick?.invoke()
                    },
                    enabled = updatedOnManualLoginClick != null,
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }
}
