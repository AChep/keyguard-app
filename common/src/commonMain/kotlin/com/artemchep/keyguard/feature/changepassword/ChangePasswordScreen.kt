package com.artemchep.keyguard.feature.changepassword

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChangePasswordScreen() {
    val loadableState = changePasswordState()
    loadableState.fold(
        ifLoading = {
            ChangePasswordScaffoldSkeleton()
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
fun ChangePasswordScaffoldSkeleton() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
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
        ChangePasswordContentSkeleton()
    }
}

private fun LazyListScope.ChangePasswordContentSkeleton() {
    item("password.current.skeleton") {
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
    item("password.current.new.spacer.skeleton") {
        Spacer(Modifier.height(8.dp))
    }
    item("password.new.skeleton") {
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
    item("biometric.disclaimer.spacer.skeleton") {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
    }
    item("disclaimer.icon.spacer.skeleton") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
    item("disclaimer.text.spacer.skeleton") {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }
    item("disclaimer.local.skeleton") {
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
    }
    item("disclaimer.local.line2.skeleton") {
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
    }
    item("disclaimer.abuse.skeleton") {
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            emphasis = MediumEmphasisAlpha,
        )
    }
}

@Composable
private fun ChangePasswordCurrentPasswordItem(
    state: ChangePasswordState,
) {
    PasswordFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = state.password.current,
        label = stringResource(Res.string.current_password),
    )
}

@Composable
private fun ChangePasswordNewPasswordItem(
    state: ChangePasswordState,
) {
    PasswordFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = state.password.new,
        label = stringResource(Res.string.new_password),
        trailing = {
            AutofillButton(
                key = "password",
                password = true,
                onValueChange = state.password.new.onSetText,
            )
        },
    )
}

@Composable
private fun ChangePasswordDisclaimerIconItem() {
    Icon(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        imageVector = Icons.Outlined.Info,
        contentDescription = null,
        tint = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
    )
}

@Composable
private fun ChangePasswordDisclaimerTextItem(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChangePasswordScreen(
    state: ChangePasswordState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
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
        floatingActionState = rememberChangePasswordFabState(state),
        floatingActionButton = {
            ChangePasswordFab(
                isLoading = state.isLoading,
                text = stringResource(Res.string.changepassword_change_password_button),
            )
        },
    ) {
        ChangePasswordContent(
            state = state,
        )
    }
}

@Composable
fun rememberChangePasswordFabState(
    state: ChangePasswordState,
): State<FabState?> = rememberChangePasswordFabState(
    onConfirm = state.onConfirm,
)

@Composable
internal fun rememberChangePasswordFabState(
    onConfirm: (() -> Unit)?,
): State<FabState?> {
    val fabState = if (onConfirm != null) {
        FabState(
            onClick = onConfirm,
            model = null,
        )
    } else {
        null
    }
    return rememberUpdatedState(newValue = fabState)
}

@Composable
internal fun FabScope.ChangePasswordFab(
    isLoading: Boolean,
    text: String,
) {
    DefaultFab(
        icon = {
            Crossfade(
                targetState = isLoading,
            ) { isLoading ->
                if (isLoading) {
                    KeyguardLoadingIndicator()
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
                text = text,
            )
        },
    )
}

private fun LazyListScope.ChangePasswordContent(
    state: ChangePasswordState,
) {
    item("password.current") {
        ChangePasswordCurrentPasswordItem(state = state)
    }
    item("password.current.new.spacer") {
        Spacer(Modifier.height(8.dp))
    }
    item("password.new") {
        ChangePasswordNewPasswordItem(state = state)
    }
    item("biometric.disclaimer.spacer") {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
    }
    item("disclaimer.icon") {
        ChangePasswordDisclaimerIconItem()
    }
    item("disclaimer.local.spacer") {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }
    item("disclaimer.local") {
        ChangePasswordDisclaimerTextItem(
            text = stringResource(Res.string.changepassword_disclaimer_local_note),
        )
    }
    item("disclaimer.abuse.spacer") {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }
    item("disclaimer.abuse") {
        ChangePasswordDisclaimerTextItem(
            text = stringResource(Res.string.changepassword_disclaimer_abuse_note),
        )
    }
}
