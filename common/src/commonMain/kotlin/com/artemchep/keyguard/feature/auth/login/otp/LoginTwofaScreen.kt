package com.artemchep.keyguard.feature.auth.login.otp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import arrow.core.right
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.yubikey.YubiKeyManual
import com.artemchep.keyguard.feature.yubikey.YubiKeyNfcCard
import com.artemchep.keyguard.feature.yubikey.YubiKeyUsbCard
import com.artemchep.keyguard.feature.yubikey.rememberYubiKey
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.grid.SimpleGridLayout
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoginTwofaScreen(
    args: LoginTwofaRoute.Args,
    transmitter: RouteResultTransmitter<Unit>,
) {
    val state = produceLoginTwofaScreenState(
        args = args,
        transmitter = transmitter,
    )
    LoginTwofaScreenContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginTwofaScreenContent(
    state: TwoFactorState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = remember {
        LoginOtpScreenScope()
    }
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.addaccount2fa_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val holder = state.state as? LoginTwofaState.HasPrimaryAction
            val primaryAction = holder?.primaryAction
            val fabState = if (primaryAction != null) {
                val fabOnClick = primaryAction.onClick
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            val holder = state.state as? LoginTwofaState.HasPrimaryAction
            val action = holder?.primaryAction
            DefaultFab(
                icon = {
                    Crossfade(
                        modifier = Modifier
                            .size(24.dp),
                        targetState = action?.icon,
                    ) { icon ->
                        when (icon) {
                            LoginTwofaState.PrimaryAction.Icon.LOGIN ->
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Login,
                                    contentDescription = null,
                                )

                            LoginTwofaState.PrimaryAction.Icon.LAUNCH ->
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Launch,
                                    contentDescription = null,
                                )

                            LoginTwofaState.PrimaryAction.Icon.LOADING ->
                                CircularProgressIndicator(
                                    color = LocalContentColor.current,
                                )

                            null -> {
                                // Draw nothing.
                            }
                        }
                    }
                },
                text = {
                    Text(action?.text.orEmpty())
                },
            )
        },
        overlay = {
            val isLoading by state.loadingState.collectAsState()
            DefaultProgressBar(
                modifier = Modifier,
                visible = isLoading,
            )
        },
    ) {
        TwoFactorProviderSelector(
            items = state.providers,
        )
        Spacer(Modifier.height(8.dp))

        // Content
        val s = state.state
        Column {
            when (s) {
                is LoginTwofaState.Skeleton -> LoginOtpScreenContentSkeleton(scope, s)
                is LoginTwofaState.Unsupported -> LoginOtpScreenContentUnsupported(scope, s)
                is LoginTwofaState.Authenticator -> LoginOtpScreenContentAuthenticator(scope, s)
                is LoginTwofaState.YubiKey -> LoginOtpScreenContentYubiKey(scope, s)
                is LoginTwofaState.Email -> LoginOtpScreenContentEmail(scope, s)
                is LoginTwofaState.Fido2WebAuthn -> LoginOtpScreenContentFido2WebAuthnBrowser(
                    scope,
                    s,
                )

                is LoginTwofaState.Duo -> LoginOtpScreenContentDuoWebView(scope, s)
            }
        }
    }
}

class LoginOtpScreenScope(
    initialFocusRequested: Boolean = false,
) {
    val initialFocusRequestedState = mutableStateOf(initialFocusRequested)

    @Composable
    fun initialFocusRequesterEffect(): FocusRequester {
        val focusRequester = remember { FocusRequester() }
        // Auto focus the text field
        // on launch.
        LaunchedEffect(focusRequester) {
            var initialFocusRequested by initialFocusRequestedState
            if (!initialFocusRequested) {
                focusRequester.requestFocus()
                // do not request it the second time
                initialFocusRequested = true
            }
        }
        return focusRequester
    }
}

@Composable
private fun TwoFactorProviderSelector(
    items: List<TwoFactorProviderItem>,
) {
    val dropdown = remember(items) {
        items
            .map { type ->
                FlatItemAction(
                    id = type.key,
                    title = TextHolder.Value(type.title),
                    onClick = type.onClick,
                )
            }
    }
    FlatDropdown(
        content = {
            FlatItemTextContent(
                title = {
                    val selectedTitle = remember(items) {
                        val type = items.firstOrNull { it.checked }
                        type?.title.orEmpty()
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                        DropdownIcon()
                    }
                },
            )
        },
        dropdown = dropdown,
    )
}

@Composable
private fun ColumnScope.LoginOtpScreenContentSkeleton(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Skeleton,
) {
    val contentColor = LocalContentColor.current.combineAlpha(DisabledEmphasisAlpha)
    Box(
        modifier = Modifier
            .height(13.dp)
            .fillMaxWidth(0.70f)
            .shimmer()
            .padding(horizontal = Dimens.horizontalPadding)
            .clip(MaterialTheme.shapes.medium)
            .background(contentColor.copy(alpha = 0.28f)),
    )
    Spacer(Modifier.height(32.dp))
    Box(
        modifier = Modifier
            .height(56.dp)
            .fillMaxWidth()
            .shimmer()
            .padding(horizontal = Dimens.horizontalPadding)
            .clip(MaterialTheme.shapes.medium)
            .background(contentColor),
    )
    Spacer(Modifier.height(16.dp))
    val height = 18.dp
    Row(
        modifier = Modifier
            .height(height)
            .padding(horizontal = Dimens.horizontalPadding),
    ) {
        Box(
            modifier = Modifier
                .width(height)
                .fillMaxHeight()
                .shimmer()
                .clip(MaterialTheme.shapes.medium)
                .background(contentColor),
        )
        Spacer(Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.33f)
                .fillMaxHeight()
                .shimmer()
                .clip(MaterialTheme.shapes.medium)
                .background(contentColor),
        )
    }
}

@Composable
private fun ColumnScope.LoginOtpScreenContentUnsupported(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Unsupported,
) {
    val contentColor = LocalContentColor.current
        .combineAlpha(alpha = MediumEmphasisAlpha)
    Icon(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        imageVector = Icons.Outlined.Construction,
        contentDescription = null,
        tint = contentColor,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.addaccount2fa_unsupported_note),
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
    val updatedOnLaunchWebVault by rememberUpdatedState(state.onLaunchWebVault)
    ExpandedIfNotEmpty(
        valueOrNull = Unit.takeIf { state.onLaunchWebVault != null },
    ) {
        TextButton(
            modifier = Modifier
                .padding(top = 16.dp)
                .padding(
                    vertical = 4.dp,
                    horizontal = 4.dp,
                ),
            onClick = {
                updatedOnLaunchWebVault?.invoke()
            },
        ) {
            Text(
                text = stringResource(Res.string.launch_web_vault),
            )
        }
    }
}

@Composable
private fun ColumnScope.LoginOtpScreenContentAuthenticator(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Authenticator,
) {
    val focusRequester = screenScope.initialFocusRequesterEffect()

    val primaryActionOnClick = state.primaryAction?.onClick
    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (primaryActionOnClick != null) {
            // lambda
            {
                primaryActionOnClick()
            }
        } else {
            null
        }

    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.addaccount2fa_otp_note),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        // TODO: Should be Authenticator code, when compose autofill
        //  adds a support for that.
        fieldModifier = Modifier
            .focusRequester(focusRequester),
        label = stringResource(Res.string.verification_code),
        value = state.code,
        textStyle = TextStyle(
            fontFamily = monoFontFamily,
        ),
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Number,
            imeAction = when {
                keyboardOnGo != null -> ImeAction.Go
                else -> ImeAction.Default
            },
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
        maxLines = 1,
        singleLine = true,
        leading = {
            IconBox(
                main = Icons.Outlined.KeyguardTwoFa,
            )
        },
    )
    Spacer(Modifier.height(8.dp))
    FlatItemLayout(
        leading = {
            Checkbox(
                checked = state.rememberMe.checked,
                enabled = state.rememberMe.onChange != null,
                onCheckedChange = null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = state.rememberMe.onChange?.partially1(!state.rememberMe.checked),
    )
}

@Composable
private fun ColumnScope.LoginOtpScreenContentEmail(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Email,
) {
    val focusRequester = screenScope.initialFocusRequesterEffect()

    val primaryActionOnClick = state.primaryAction?.onClick
    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (primaryActionOnClick != null) {
            // lambda
            {
                primaryActionOnClick()
            }
        } else {
            null
        }

    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.addaccount2fa_email_note, state.email.orEmpty()),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        // TODO: Should be Email code, when compose autofill
        //  adds a support for that.
        fieldModifier = Modifier
            .focusRequester(focusRequester),
        label = stringResource(Res.string.verification_code),
        value = state.code,
        textStyle = TextStyle(
            fontFamily = monoFontFamily,
        ),
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Number,
            imeAction = when {
                keyboardOnGo != null -> ImeAction.Go
                else -> ImeAction.Default
            },
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
        maxLines = 1,
        singleLine = true,
        leading = {
            IconBox(
                main = Icons.Outlined.KeyguardTwoFa,
            )
        },
    )
    Spacer(Modifier.height(8.dp))
    AnimatedVisibility(
        visible = state.emailResend != null,
    ) {
        FlatItemLayout(
            leading = {
                Box {
                    Icon(Icons.Outlined.MailOutline, null)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            null,
                            Modifier
                                .padding(1.dp)
                                .size(12.dp),
                        )
                    }
                }
            },
            content = {
                Text(
                    text = stringResource(Res.string.resend_verification_code),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            onClick = state.emailResend,
        )
    }
    FlatItemLayout(
        leading = {
            Checkbox(
                checked = state.rememberMe.checked,
                enabled = state.rememberMe.onChange != null,
                onCheckedChange = null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = state.rememberMe.onChange?.partially1(!state.rememberMe.checked),
    )
}

// TODO: Switch to WebView implementation after
//  https://bugs.chromium.org/p/chromium/issues/detail?id=1284805
//  is fixed.
@Composable
expect fun ColumnScope.LoginOtpScreenContentFido2WebAuthnWebView(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Fido2WebAuthn,
)

@Composable
expect fun ColumnScope.LoginOtpScreenContentFido2WebAuthnBrowser(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Fido2WebAuthn,
)

@Composable
expect fun ColumnScope.LoginOtpScreenContentDuoWebView(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.Duo,
)

@Composable
private fun ColumnScope.LoginOtpScreenContentYubiKey(
    screenScope: LoginOtpScreenScope,
    state: LoginTwofaState.YubiKey,
) {
    val yubi = rememberYubiKey(
        send = state.onComplete,
    )

    val platform = CurrentPlatform
    val manual = remember(platform, yubi) {
        derivedStateOf {
            if (platform is Platform.Desktop) {
                return@derivedStateOf true
            }

            val usb = yubi.usbState.value.getOrNull()
            val nfc = yubi.nfcState.value.getOrNull()
            usb?.enabled == false &&
                    nfc?.enabled == false
        }
    }
    AnimatedVisibility(manual.value) {
        val updatedSend by rememberUpdatedState(state.onComplete)
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            YubiKeyManual(
                modifier = Modifier
                    .fillMaxWidth(),
                onSend = { value ->
                    val result = value.right()
                    updatedSend?.invoke(result)
                },
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            HorizontalDivider()
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
        }
    }
    SimpleGridLayout(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPaddingHalf),
    ) {
        YubiKeyUsbCard(
            state = yubi.usbState,
        )
        YubiKeyNfcCard(
            state = yubi.nfcState,
        )
    }
    FlatItemLayout(
        leading = {
            Checkbox(
                checked = state.rememberMe.checked,
                enabled = state.rememberMe.onChange != null,
                onCheckedChange = null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = state.rememberMe.onChange?.partially1(!state.rememberMe.checked),
    )
}
