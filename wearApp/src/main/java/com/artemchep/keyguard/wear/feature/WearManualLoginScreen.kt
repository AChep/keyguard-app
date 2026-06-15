package com.artemchep.keyguard.wear.feature

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.LoginState
import com.artemchep.keyguard.feature.auth.bitwarden.LoginStateItem
import com.artemchep.keyguard.feature.auth.bitwarden.produceBitwardenLoginScreenState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_captcha_need_client_secret_label
import com.artemchep.keyguard.res.addaccount_captcha_need_client_secret_note
import com.artemchep.keyguard.res.addaccount_create_an_account_title
import com.artemchep.keyguard.res.addaccount_disclaimer_bitwarden_label
import com.artemchep.keyguard.res.addaccount_header_title
import com.artemchep.keyguard.res.addaccount_region_section
import com.artemchep.keyguard.res.addaccount_sign_in_button
import com.artemchep.keyguard.ui.BiFlatTextField
import com.artemchep.keyguard.ui.ConcealedFlatTextField
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.EmailFlatTextField
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.WearDotsDivider
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import kotlinx.coroutines.flow.map
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
            val updatedOnLoginClick by rememberUpdatedState(state.onLoginClick)
            val focusManager = LocalFocusManager.current
            val focusRequester = remember { FocusRequester() }
            val isEnvironmentVisible by rememberSaveable {
                mutableStateOf(false)
            }
            LaunchedEffect(focusRequester) {
                val isWatch = CurrentPlatform.hasWatch()
                if (!isWatch) {
                    focusRequester.requestFocus()
                }
            }
            val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
                if (updatedOnLoginClick != null) {
                    {
                        updatedOnLoginClick?.invoke()
                    }
                } else {
                    null
                }
            val keyboardOnNext: KeyboardActionScope.() -> Unit = {
                focusManager.moveFocus(FocusDirection.Down)
            }

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
            WearScaffoldScreen(
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
                    DefaultEdgeButton(
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
            ) { transformationSpec ->
                WearBitwardenLoginContent(
                    loginState = state,
                    isEnvironmentVisible = isEnvironmentVisible,
                    focusRequester = focusRequester,
                    keyboardOnGo = keyboardOnGo,
                    keyboardOnNext = keyboardOnNext,
                    transformationSpec = transformationSpec,
                )
            }
        }
    }
}

private fun TransformingLazyColumnScope.WearBitwardenLoginContent(
    loginState: LoginState,
    isEnvironmentVisible: Boolean,
    focusRequester: FocusRequester,
    keyboardOnGo: (KeyboardActionScope.() -> Unit)?,
    keyboardOnNext: KeyboardActionScope.() -> Unit,
    transformationSpec: TransformationSpec,
) {
    item("disclaimer") {
        WearListLabel(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            text = stringResource(Res.string.addaccount_disclaimer_bitwarden_label),
            textAlign = TextAlign.Start,
            transformation = SurfaceTransformation(transformationSpec),
        )
    }

    if (loginState.regionItems.isNotEmpty()) {
        item("regions") {
            val navigationController = LocalNavigationController.current
            val titleRegionPicker = stringResource(Res.string.addaccount_region_section)
            val currentRegion = loginState.regionItems
                .firstOrNull { it.checked }
                ?: loginState.regionItems.firstOrNull()
            val actions = remember(loginState.regionItems) {
                loginState.regionItems
                    .map { item ->
                        FlatItemAction(
                            id = item.key,
                            icon = Icons.Outlined.Public,
                            title = item.title,
                            selected = item.checked,
                            onClick = item.onClick,
                        )
                    }
            }
            WearListAction(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                title = {
                    Text(
                        text = currentRegion
                            ?.let { textResource(it.title) }
                            .orEmpty(),
                    )
                },
                onClick = {
                    val route = WearPickerRoute(
                        title = titleRegionPicker,
                        actions = actions,
                    )
                    val intent = NavigationIntent.NavigateToRoute(route = route)
                    navigationController.queue(intent)
                },
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }

    item("divider") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        WearDotsDivider(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            transformation = surfaceTransformation,
        )
    }

    item("email") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        EmailFlatTextField(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation)
                .focusRequester(focusRequester),
            fieldModifier = Modifier
                .contentType(ContentType.EmailAddress),
            value = loginState.email,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = keyboardOnNext,
            ),
        )
    }

    item("password") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        val passwordIsLastField = loginState.clientSecret == null && !isEnvironmentVisible
        PasswordFlatTextField(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation),
            fieldModifier = Modifier
                .contentType(ContentType.Password),
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
    }

    val clientSecretOrNull = loginState.clientSecret
    if (clientSecretOrNull != null) {
        item("client_secret.note") {
            WearListLabel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .transformedHeight(this, transformationSpec),
                text = stringResource(Res.string.addaccount_captcha_need_client_secret_note),
                textAlign = TextAlign.Start,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
        item("client_secret.field") {
            val surfaceTransformation = SurfaceTransformation(transformationSpec)
            val clientSecretIsLastField = !isEnvironmentVisible
            ConcealedFlatTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec)
                    .surfaceTransformation(surfaceTransformation),
                label = stringResource(Res.string.addaccount_captcha_need_client_secret_label),
                value = clientSecretOrNull,
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

    if (loginState.showCustomEnv) {
        WearBitwardenLoginItems(
            items = loginState.items,
            transformationSpec = transformationSpec,
        )
    }

    loginState.onRegisterClick?.let { onClick ->
        item("register") {
            WearBitwardenLoginRegisterButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec)
                    .surfaceTransformation(SurfaceTransformation(transformationSpec)),
                onClick = onClick,
            )
        }
    }
}

private fun TransformingLazyColumnScope.WearBitwardenLoginItems(
    items: List<LoginStateItem>,
    transformationSpec: TransformationSpec,
) {
    items.forEachIndexed { index, loginItem ->
        item("custom_env.${loginItem.id}") {
            val shapeState = getShapeState(
                list = items,
                index = index,
                predicate = { el, _ ->
                    el is LoginStateItem.Url ||
                            el is LoginStateItem.HttpHeader
                },
            )
            WearBitwardenLoginItem(
                loginItem = loginItem,
                shapeState = shapeState,
                transformationSpec = transformationSpec,
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.WearBitwardenLoginItem(
    loginItem: LoginStateItem,
    shapeState: Int,
    transformationSpec: TransformationSpec,
) = when (loginItem) {
    is LoginStateItem.Url -> WearBitwardenLoginItemUrl(
        item = loginItem,
        shapeState = shapeState,
        transformationSpec = transformationSpec,
    )

    is LoginStateItem.HttpHeader -> WearBitwardenLoginItemField(
        item = loginItem,
        shapeState = shapeState,
        transformationSpec = transformationSpec,
    )

    is LoginStateItem.Section -> WearSectionHeader(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec),
        title = loginItem.text,
        transformation = SurfaceTransformation(transformationSpec),
    )

    is LoginStateItem.Label -> WearListLabel(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec),
        text = loginItem.text,
        textAlign = TextAlign.Start,
        transformation = SurfaceTransformation(transformationSpec),
    )

    is LoginStateItem.Add -> WearBitwardenLoginItemAdd(
        item = loginItem,
        transformationSpec = transformationSpec,
    )
}

@Composable
private fun TransformingLazyColumnItemScope.WearBitwardenLoginItemUrl(
    item: LoginStateItem.Url,
    shapeState: Int,
    transformationSpec: TransformationSpec,
) {
    val field by item.state.flow.collectAsState()
    UrlFlatTextField(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .surfaceTransformation(SurfaceTransformation(transformationSpec)),
        value = field.text,
        label = field.label,
    )
}

@Composable
private fun TransformingLazyColumnItemScope.WearBitwardenLoginItemField(
    item: LoginStateItem.HttpHeader,
    shapeState: Int,
    transformationSpec: TransformationSpec,
) {
    val field by item.state.flow.collectAsState()
    val localState by remember(item.state.flow) {
        item.state.flow
            .map { a ->
                a.options + item.options
            }
    }.collectAsState(null)
    BiFlatTextField(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
            .surfaceTransformation(SurfaceTransformation(transformationSpec)),
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
private fun TransformingLazyColumnItemScope.WearBitwardenLoginItemAdd(
    item: LoginStateItem.Add,
    transformationSpec: TransformationSpec,
) {
    var dropdownShown by remember { mutableStateOf(false) }
    if (item.actions.isEmpty()) {
        dropdownShown = false
    }

    val onDismissRequest = remember {
        {
            dropdownShown = false
        }
    }

    Button(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .transformedHeight(this, transformationSpec)
            .surfaceTransformation(SurfaceTransformation(transformationSpec))
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = {
            if (item.actions.size == 1) {
                item.actions.first()
                    .onClick?.invoke()
            } else {
                dropdownShown = true
            }
        },
        colors = ButtonDefaults.filledTonalButtonColors(),
        elevation = ButtonDefaults.filledTonalButtonElevation(),
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(ButtonDefaults.IconSpacing),
        )
        Text(
            text = item.text,
        )

        KeyguardDropdownMenu(
            expanded = dropdownShown,
            onDismissRequest = onDismissRequest,
        ) {
            item.actions.forEach { action ->
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}

@Composable
private fun WearBitwardenLoginRegisterButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(),
        elevation = ButtonDefaults.filledTonalButtonElevation(),
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.KeyguardWebsite,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(ButtonDefaults.IconSpacing),
        )
        Text(
            text = stringResource(Res.string.addaccount_create_an_account_title),
        )
    }
}
