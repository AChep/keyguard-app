package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.FlatSurfaceExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
fun KeePassLoginScreen(
    transmitter: RouteResultTransmitter<Unit>,
) {
    val state = produceKeePassLoginScreenState(
    )
    state.fold(
        ifLoading = {
            LoginContentSkeleton()
        },
        ifOk = { okState ->
            KeePassLoginScreen(
                transmitter = transmitter,
                state = okState,
            )
        },
    )
}

@Composable
fun KeePassLoginScreen(
    transmitter: RouteResultTransmitter<Unit>,
    state: KeePassLoginState,
) {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.sideEffects.onSuccessFlow) {
        // Notify that we have successfully logged in, and that
        // the caller can now decide what to do.
        transmitter.invoke(Unit)
    }
    CollectedEffect(state.sideEffects.onErrorFlow) { error ->
        // Do nothing.
    }
    FilePickerEffect(state.sideEffects.filePickerIntentFlow)

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
                        text = stringResource(Res.string.addkeepass_header_title),
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
                .padding(horizontal = Dimens.textHorizontalPadding)
                .fillMaxWidth(0.8f),
            style = MaterialTheme.typography.bodyMedium,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding)
                .fillMaxWidth(0.88f),
            style = MaterialTheme.typography.bodyMedium,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding)
                .fillMaxWidth(0.3f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.textHorizontalPadding),
        )
        Spacer(Modifier.height(8.dp))
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.textHorizontalPadding),
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun LoginContent(
    loginState: KeePassLoginState,
) {
    var isEnvironmentVisible by rememberSaveable {
        mutableStateOf(false)
    }

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
                        text = stringResource(Res.string.addkeepass_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val action by loginState.actionState
                .collectAsState()

            val fabOnClick = action?.onClick
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
        val dbFileState by loginState.dbFileState
            .collectAsState()
        val keyFileState by loginState.keyFileState
            .collectAsState()
        val passwordState by loginState.password
            .collectAsState()
        val tabsState by loginState.tabsState
            .collectAsState()

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
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.addkeepass_header_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        FlatSimpleNote(
            modifier = Modifier,
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.addkeepass_use_at_your_own_risk_beta_text),
        )
        Spacer(Modifier.height(16.dp))

        val allTabs = tabsState.items
        val selectedTab = remember(tabsState.items) {
            tabsState.items
                .firstOrNull { it.checked }
        }
        SegmentedButtonGroup(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.buttonHorizontalPadding,
                ),
            tabState = rememberUpdatedState(selectedTab),
            tabs = allTabs,
            onClick = { tab ->
                tab.onClick?.invoke()
            },
        )
        Spacer(Modifier.height(8.dp))
        if (selectedTab == null) {
            return@ScaffoldColumn
        }

        Section()

        Spacer(
            modifier = Modifier
                .height(8.dp),
        )

        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatSurfaceExpressive(
                modifier = Modifier
                    .weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .padding(
                            horizontal = Dimens.contentPadding,
                            vertical = 10.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val file = dbFileState.file
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = file?.name.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    val size = remember(file?.size) {
                        file?.size
                            ?.let(::humanReadableByteCountSI)
                    }
                    Text(
                        text = size.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
            IconButton(
                onClick = {
                    dbFileState.onClick()
                },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.outlinedIconButtonColors(),
            ) {
                IconBox(
                    main = Icons.Outlined.FileOpen,
                )
            }
            Spacer(
                modifier = Modifier
                    .width(8.dp), // to match the inner padding of the field
            )
            Spacer(
                modifier = Modifier
                    .width(Dimens.buttonHorizontalPadding),
            )
        }

        Spacer(
            modifier = Modifier
                .height(8.dp),
        )

        val passwordFieldShapeState = if (keyFileState.file != null) {
            ShapeState.START
        } else ShapeState.ALL
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            shapeState = passwordFieldShapeState,
            testTag = "field:password",
            value = passwordState,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = keyboardOnGo,
            ),
        )
        ExpandedIfNotEmpty(keyFileState.file) { file ->
            FlatSurfaceExpressive(
                modifier = Modifier
                    .padding(
                        top = 2.dp,
                    ),
                shapeState = ShapeState.END,
            ) {
                Row(
                    modifier = Modifier
                        .padding(
                            horizontal = Dimens.contentPadding,
                            vertical = 10.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f),
                        text = file.name.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    val size = remember(file.size) {
                        file.size
                            ?.let(::humanReadableByteCountSI)
                    }
                    Text(
                        text = size.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = Dimens.buttonHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = ButtonDefaults.outlinedButtonBorder(),
                onClick = keyFileState.onClick,
            ) {
                IconBox(
                    main = Icons.Outlined.Key,
                    secondary = Icons.Outlined.FileOpen,
                )
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )

                val text = if (keyFileState.file != null) {
                    stringResource(Res.string.replace_key_file)
                } else {
                    stringResource(Res.string.select_key_file)
                }
                Text(
                    modifier = Modifier
                        .animateContentSize(),
                    text = text,
                )
            }
            if (keyFileState.file != null) {
                val updatedOnClear by rememberUpdatedState(keyFileState.onClear)
                Button(
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(),
                    onClick = {
                        updatedOnClear?.invoke()
                    },
                ) {
                    IconBox(
                        main = Icons.Outlined.Clear,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )

                    Text(
                        modifier = Modifier
                            .animateContentSize(),
                        text = stringResource(Res.string.clear_file),
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.backup_disclaimer_title),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.backup_disclaimer_text),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}
