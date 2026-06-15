package com.artemchep.keyguard.wear.feature.auth.bitwarden.twofactor

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.produceLoginTwofaScreenState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProvider
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount2fa_email_note
import com.artemchep.keyguard.res.addaccount2fa_header_title
import com.artemchep.keyguard.res.addaccount2fa_otp_note
import com.artemchep.keyguard.res.addaccount2fa_unsupported_note
import com.artemchep.keyguard.res.launch_web_vault
import com.artemchep.keyguard.res.resend_verification_code
import com.artemchep.keyguard.res.verification_code
import com.artemchep.keyguard.ui.ConcealedFlatTextField
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearBitwardenLoginTwofaScreen(
    args: BitwardenLoginTwofaRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)

    val items = rememberTwoFactorProvidersList(args.providers)
    WearScaffoldScreen(
        title = stringResource(Res.string.addaccount2fa_header_title),
    ) { transformationSpec ->
        items(items, key = { it.key }) { providerItem ->
            WearListAction(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                title = {
                    Text(
                        text = providerItem.title(),
                        textAlign = TextAlign.Start,
                    )
                },
                onClick = if (providerItem.supported) {
                    // lambda
                    {
                        val route = registerRouteResultReceiver(
                            route = WearBitwardenLoginTwofaProviderRoute(
                                args = args,
                                provider = providerItem.provider,
                            ),
                        ) {
                            updatedNavigationController.queue(NavigationIntent.Pop)
                            transmitter(Unit)
                        }
                        updatedNavigationController.queue(NavigationIntent.NavigateToRoute(route))
                    }
                } else {
                    null
                },
                enabled = providerItem.supported,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
}

@Composable
private fun rememberTwoFactorProvidersList(
    providers: List<TwoFactorProviderArgument>,
) = remember(providers) {
    providers
        .mapIndexedNotNull { index, provider ->
            val info = TwoFactorProvider.state[provider.type]
                ?: return@mapIndexedNotNull null
            WearTwofaProviderItem(
                key = "${provider.type.name}.$index",
                provider = provider,
                title = info.name,
                supported = info.supported,
                priority = info.priority,
            )
        }
        .sortedWith(wearTwofaProviderComparator)
}

//
// Providers
//

@Composable
fun WearBitwardenLoginTwofaProviderScreen(
    args: BitwardenLoginTwofaRoute.Args,
    provider: TwoFactorProviderArgument,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val filteredArgs = remember(args, provider) {
        args.copy(
            providers = listOf(provider),
        )
    }
    val state = produceLoginTwofaScreenState(
        args = filteredArgs,
        transmitter = transmitter,
        defaultRememberMe = true, // we want to avoid extra clicks on wear platform
    )
    val loading by state.loadingState.collectAsStateWithLifecycle()
    val title = remember(provider.type) {
        TwoFactorProvider.state[provider.type]?.name
    }
    val primaryAction = (state.state as? BitwardenLoginTwofaState.HasPrimaryAction)
        ?.primaryAction

    WearScaffoldScreen(
        title = title?.let { stringResource(it) } ?: provider.type.name,
        floatingActionState = rememberPrimaryActionFabState(primaryAction),
        floatingActionButton = {
            if (primaryAction != null) {
                DefaultEdgeButton(
                    icon = {
                        when (primaryAction.icon) {
                            BitwardenLoginTwofaState.PrimaryAction.Icon.LOGIN -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Login,
                                    contentDescription = null,
                                )
                            }

                            BitwardenLoginTwofaState.PrimaryAction.Icon.LAUNCH -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Launch,
                                    contentDescription = null,
                                )
                            }

                            BitwardenLoginTwofaState.PrimaryAction.Icon.LOADING -> {
                                KeyguardLoadingIndicator()
                            }
                        }
                    },
                    text = {
                        Text(primaryAction.text)
                    },
                )
            }
        },
        overlay = {
            WearScaffoldLoader(
                modifier = Modifier,
                visible = state.state is BitwardenLoginTwofaState.Skeleton || loading,
            )
        },
    ) { transformationSpec ->
        WearBitwardenLoginTwofaStateContent(
            state = state.state,
            loading = loading,
            transformationSpec = transformationSpec,
        )
    }
}

@Composable
private fun rememberPrimaryActionFabState(
    primaryAction: BitwardenLoginTwofaState.PrimaryAction?,
): State<FabState?> = rememberUpdatedState(
    newValue = primaryAction?.let { action ->
        FabState(
            onClick = action.onClick,
            model = action.icon,
        )
    },
)

private fun TransformingLazyColumnScope.WearBitwardenLoginTwofaStateContent(
    state: BitwardenLoginTwofaState,
    loading: Boolean,
    transformationSpec: TransformationSpec,
) {
    when (state) {
        BitwardenLoginTwofaState.Skeleton -> {
            // Do nothing
        }

        is BitwardenLoginTwofaState.YubiKey,
        is BitwardenLoginTwofaState.Duo,
        is BitwardenLoginTwofaState.Fido2WebAuthn,
        is BitwardenLoginTwofaState.Unsupported,
            -> {
            item("unsupported") {
                WearListLabel(
                    text = stringResource(Res.string.addaccount2fa_unsupported_note),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }

        is BitwardenLoginTwofaState.Authenticator -> {
            WearBitwardenLoginTwofaCodeSection(
                keyPrefix = "authenticator",
                transformationSpec = transformationSpec,
                note = { stringResource(Res.string.addaccount2fa_otp_note) },
                code = state.code,
                onSubmit = state.primaryAction?.onClick,
                loading = loading,
                resendAction = null,
                resendLabel = null,
            )
        }

        is BitwardenLoginTwofaState.Email -> {
            WearBitwardenLoginTwofaCodeSection(
                keyPrefix = "email",
                transformationSpec = transformationSpec,
                note = {
                    stringResource(
                        Res.string.addaccount2fa_email_note,
                        state.email.orEmpty(),
                    )
                },
                code = state.code,
                onSubmit = state.primaryAction?.onClick,
                loading = loading,
                resendAction = state.emailResend,
                resendLabel = { stringResource(Res.string.resend_verification_code) },
            )
        }

        is BitwardenLoginTwofaState.EmailNewDevice -> {
            WearBitwardenLoginTwofaCodeSection(
                keyPrefix = "email_new_device",
                transformationSpec = transformationSpec,
                note = {
                    stringResource(
                        Res.string.addaccount2fa_email_note,
                        state.email.orEmpty(),
                    )
                },
                code = state.code,
                onSubmit = state.primaryAction?.onClick,
                loading = loading,
                resendAction = state.emailResend,
                resendLabel = { stringResource(Res.string.resend_verification_code) },
            )
        }
    }
}

private fun TransformingLazyColumnScope.WearBitwardenLoginTwofaCodeSection(
    keyPrefix: String,
    transformationSpec: TransformationSpec,
    note: @Composable () -> String,
    code: TextFieldModel2,
    onSubmit: (() -> Unit)?,
    loading: Boolean,
    resendAction: (() -> Unit)?,
    resendLabel: (@Composable () -> String)?,
) {
    item("$keyPrefix.note") {
        WearListLabel(
            modifier = Modifier
                .transformedHeight(this, transformationSpec),
            text = note(),
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
    item("$keyPrefix.field") {
        WearBitwardenLoginTwofaCodeField(
            modifier = Modifier
                .transformedHeight(this, transformationSpec),
            transformationSpec = transformationSpec,
            code = code,
            onSubmit = onSubmit,
        )
    }
    if (resendAction != null && resendLabel != null) {
        item("$keyPrefix.resend") {
            WearListAction(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                title = {
                    val text = resendLabel()
                    Text(text)
                },
                onClick = if (!loading) resendAction else null,
                enabled = !loading,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.WearBitwardenLoginTwofaCodeField(
    modifier: Modifier,
    transformationSpec: TransformationSpec,
    code: TextFieldModel2,
    onSubmit: (() -> Unit)?,
) {
    val surfaceTransformation = SurfaceTransformation(transformationSpec)
    ConcealedFlatTextField(
        modifier = modifier
            .surfaceTransformation(surfaceTransformation),
        label = stringResource(Res.string.verification_code),
        value = code,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                onSubmit?.invoke()
            },
        ),
    )
}

@Immutable
private data class WearTwofaProviderItem(
    val key: String,
    val provider: TwoFactorProviderArgument,
    val title: org.jetbrains.compose.resources.StringResource,
    val supported: Boolean,
    val priority: Int,
) {
    @Composable
    fun title(): String = stringResource(title)
}

private val wearTwofaProviderComparator = compareBy<WearTwofaProviderItem>(
    { !it.supported },
    { -it.priority },
)
