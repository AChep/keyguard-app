package com.artemchep.keyguard.feature.changepassword

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonCheckbox
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChangePasswordScreen() {
    val loadableState = changePasswordState()
    loadableState.fold(
        ifLoading = {
            ChangePasswordSkeleton()
        },
        ifOk = { state ->
            ChangePasswordScreen(
                state = state,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordSkeleton() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.changepassword_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
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
        Spacer(Modifier.height(8.dp))
        FlatItemLayout(
            leading = {
                SkeletonCheckbox(
                    clickable = false,
                )
            },
            content = {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(0.3f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            enabled = true,
        )
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChangePasswordScreen(
    state: ChangePasswordState,
) {
    BiometricPromptEffect(state.sideEffects.showBiometricPromptFlow)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.changepassword_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = state.onConfirm
            val fabState = if (fabOnClick != null) {
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
            DefaultFab(
                icon = {
                    Crossfade(
                        targetState = state.isLoading,
                    ) { isLoading ->
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = stringResource(Res.string.changepassword_change_password_button),
                    )
                },
            )
        },
    ) {
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            value = state.password.current,
            label = stringResource(Res.string.current_password),
        )
        Spacer(Modifier.height(8.dp))
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            value = state.password.new,
            label = stringResource(Res.string.new_password),
            trailing = {
                AutofillButton(
                    key = "password",
                    password = true,
                    onValueChange = state.password.new.onChange,
                )
            },
        )
        if (state.biometric != null) {
            Spacer(Modifier.height(8.dp))
            FlatItemLayout(
                leading = {
                    Checkbox(
                        enabled = state.biometric.onChange != null,
                        checked = state.biometric.checked,
                        onCheckedChange = {
                            state.biometric.onChange?.invoke(it)
                        },
                    )
                },
                content = {
                    Text(
                        text = stringResource(Res.string.changepassword_biometric_auth_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = state.biometric.onChange?.partially1(!state.biometric.checked),
            )
        }
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.changepassword_disclaimer_local_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.changepassword_disclaimer_abuse_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}
