package com.artemchep.keyguard.feature.auth.login

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.auth.common.autofill
import com.artemchep.keyguard.feature.auth.login.otp.LoginTwofaRoute
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.BiFlatTextField
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.ConcealedFlatTextField
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.EmailFlatTextField
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map

@Composable
fun LoginScreen(
    transmitter: RouteResultTransmitter<Unit>,
    args: LoginRoute.Args,
) {
    val state = produceLoginScreenState(
        args = args,
    )
    state.fold(
        ifLoading = {
            LoginContentSkeleton()
        },
        ifOk = { okState ->
            LoginScreen(
                transmitter = transmitter,
                state = okState,
            )
        },
    )
}

@Composable
fun LoginScreen(
    transmitter: RouteResultTransmitter<Unit>,
    state: LoginState,
) {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.effects.onSuccessFlow) {
        // Notify that we have successfully logged in, and that
        // the caller can now decide what to do.
        transmitter.invoke(Unit)
    }
    CollectedEffect(state.effects.onErrorFlow) { error ->
        when (error) {
            // Navigate to the OTP screen with current
            // credentials and environment.
            is LoginEvent.Error.OtpRequired -> {
                val route = registerRouteResultReceiver(
                    route = LoginTwofaRoute(error.args),
                ) {
                    controller.queue(NavigationIntent.Pop)
                    transmitter.invoke(Unit)
                }
                val intent = NavigationIntent.NavigateToRoute(route)
                controller.queue(intent)
            }
        }
    }

    LoginContent(
        loginState = state,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun LoginContentSkeleton() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.addaccount_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(0.8f),
            style = MaterialTheme.typography.bodyMedium,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(0.88f),
            style = MaterialTheme.typography.bodyMedium,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(0.3f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
        Spacer(Modifier.height(8.dp))
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class,
)
@Composable
fun LoginContent(
    loginState: LoginState,
) {
    var isEnvironmentVisible by rememberSaveable {
        mutableStateOf(false)
    }

    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.addaccount_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = loginState.onLoginClick
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
                            .size(24.dp),
                        targetState = loginState.isLoading,
                    ) { isLoading ->
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                            )
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
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }
        // Auto focus the text field
        // on launch.
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }

        val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
            if (loginState.onLoginClick != null) {
                // lambda
                {
                    loginState.onLoginClick.invoke()
                }
            } else {
                null
            }
        val keyboardOnNext: KeyboardActionScope.() -> Unit = {
            focusManager.moveFocus(FocusDirection.Down)
        }

        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.addaccount_disclaimer_bitwarden_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        EmailFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .focusRequester(focusRequester),
            fieldModifier = Modifier
                .autofill(
                    value = loginState.email.state.value,
                    autofillTypes = listOf(
                        AutofillType.EmailAddress,
                    ),
                    onFill = loginState.email.onChange,
                ),
            value = loginState.email,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = keyboardOnNext,
            ),
        )
        Spacer(Modifier.height(8.dp))
        val passwordIsLastField = loginState.clientSecret == null && !isEnvironmentVisible
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            fieldModifier = Modifier
                .autofill(
                    value = loginState.password.state.value,
                    autofillTypes = listOf(
                        AutofillType.Password,
                    ),
                    onFill = loginState.password.onChange,
                ),
            value = loginState.password,
            keyboardOptions = KeyboardOptions(
                imeAction = when {
                    !passwordIsLastField -> ImeAction.Next
                    else -> ImeAction.Go
                },
            ),
            keyboardActions = KeyboardActions(
                onGo = keyboardOnGo,
                onNext = keyboardOnNext.takeUnless { passwordIsLastField },
            ),
        )
        val clientSecretOrNull = loginState.clientSecret
        ExpandedIfNotEmpty(
            valueOrNull = clientSecretOrNull,
        ) { clientSecret ->
            Column {
                Box(Modifier.height(32.dp))
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.addaccount_captcha_need_client_secret_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(Modifier.height(16.dp))
                val clientSecretIsLastField = !isEnvironmentVisible
                ConcealedFlatTextField(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    label = stringResource(Res.string.addaccount_captcha_need_client_secret_label),
                    value = clientSecret,
                    keyboardOptions = KeyboardOptions(
                        imeAction = when {
                            !clientSecretIsLastField -> ImeAction.Next
                            else -> ImeAction.Go
                        },
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = keyboardOnGo,
                        onNext = keyboardOnNext.takeUnless { clientSecretIsLastField },
                    ),
                    singleLine = true,
                    maxLines = 1,
                    leading = {
                        IconBox(
                            main = Icons.Outlined.Security,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LoginItems(
            items = loginState.regionItems,
        )
        Spacer(Modifier.height(8.dp))
        ExpandedIfNotEmpty(
            valueOrNull = Unit.takeIf { loginState.showCustomEnv },
        ) {
            LoginItems(
                items = loginState.items,
            )
        }
        loginState.onRegisterClick?.let { onClick ->
            HorizontalDivider(
                modifier = Modifier
                    .padding(
                        top = 32.dp,
                        bottom = 8.dp,
                    ),
            )
            FlatItemLayout(
                content = {
                    FlatItemTextContent(
                        title = {
                            Text(
                                text = stringResource(Res.string.addaccount_create_an_account_title),
                            )
                        },
                    )
                },
                trailing = {
                    ChevronIcon()
                },
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun LoginItems(
    modifier: Modifier = Modifier,
    items: List<LoginStateItem>,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item.id) {
                LoginItem(
                    modifier = Modifier,
                    item = item,
                )
            }
        }
    }
}

@Composable
private fun LoginItem(
    modifier: Modifier = Modifier,
    item: LoginStateItem,
) = when (item) {
    is LoginStateItem.Dropdown -> LoginItemDropdown(modifier = modifier, item = item)
    is LoginStateItem.Url -> LoginItemUrl(modifier = modifier, item = item)
    is LoginStateItem.HttpHeader -> LoginItemField(modifier = modifier, item = item)
    is LoginStateItem.Section -> LoginItemSection(modifier = modifier, item = item)
    is LoginStateItem.Label -> LoginItemLabel(modifier = modifier, item = item)
    is LoginStateItem.Add -> LoginItemAdd(modifier = modifier, item = item)
}

@Composable
private fun LoginItemDropdown(
    modifier: Modifier = Modifier,
    item: LoginStateItem.Dropdown,
) {
    val field by item.state.flow.collectAsState()
    FlatDropdown(
        modifier = modifier,
        content = {
            FlatItemTextContent(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false),
                        ) {
                            Text(
                                modifier = Modifier
                                    .animateContentSize(),
                                text = field.title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            ExpandedIfNotEmpty(field.text) { text ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                        .combineAlpha(LocalContentColor.current.alpha),
                                )
                            }
                        }
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        DropdownIcon()
                    }
                },
            )
        },
        dropdown = field.options,
    )
}

@Composable
private fun LoginItemUrl(
    modifier: Modifier = Modifier,
    item: LoginStateItem.Url,
) {
    val field by item.state.flow.collectAsState()
    UrlFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = field.text,
        label = field.label,
//        keyboardOptions = KeyboardOptions(
//            imeAction = ImeAction.Next,
//        ),
//        keyboardActions = KeyboardActions(
//            onNext = keyboardOnNext,
//        ),
    )
}

@Composable
private fun LoginItemField(
    modifier: Modifier = Modifier,
    item: LoginStateItem.HttpHeader,
) {
    val field by item.state.flow.collectAsState()
    val localState by remember(item.state.flow) {
        item.state.flow
            .map { a ->
                a.options + item.options
            }
    }.collectAsState(null)
    BiFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = field.label,
        value = field.text,
        trailing = {
            OptionsButton(
                actions = localState.orEmpty(),
            )
        },
    )
}

@Composable
private fun LoginItemSection(
    modifier: Modifier = Modifier,
    item: LoginStateItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
    )
}

@Composable
private fun LoginItemLabel(
    modifier: Modifier = Modifier,
    item: LoginStateItem.Label,
) {
    Text(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = item.text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
private fun LoginItemAdd(
    modifier: Modifier = Modifier,
    item: LoginStateItem.Add,
) {
    Column(
        modifier = modifier,
    ) {
        val dropdownShownState = remember { mutableStateOf(false) }
        if (item.actions.isEmpty()) {
            dropdownShownState.value = false
        }

        // Inject the dropdown popup to the bottom of the
        // content.
        val onDismissRequest = remember(dropdownShownState) {
            // lambda
            {
                dropdownShownState.value = false
            }
        }
        val contentColor = MaterialTheme.colorScheme.primary
        FlatItem(
            leading = {
                Icon(Icons.Outlined.Add, null, tint = contentColor)
            },
            title = {
                Text(
                    text = item.text,
                    color = contentColor,
                )

                DropdownMenu(
                    modifier = Modifier
                        .widthIn(min = DropdownMinWidth),
                    expanded = dropdownShownState.value,
                    onDismissRequest = onDismissRequest,
                ) {
                    val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                    with(scope) {
                        item.actions.forEachIndexed { index, action ->
                            DropdownMenuItemFlat(
                                action = action,
                            )
                        }
                    }
                }
            },
            onClick = {
                if (item.actions.size == 1) {
                    item.actions.first()
                        .onClick?.invoke()
                } else {
                    dropdownShownState.value = true
                }
            },
        )
    }
}
