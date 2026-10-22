package com.artemchep.keyguard.feature.home.vault.link.field

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.CompactVaultItem
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkPickerItems
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkPickerListMaxHeight
import com.artemchep.keyguard.feature.navigation.NavigationAnimation
import com.artemchep.keyguard.feature.navigation.NavigationAnimationType
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.transform
import com.artemchep.keyguard.platform.LocalAnimationFactor
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cipher_field_picker_fields_title
import com.artemchep.keyguard.res.cipher_field_picker_items_empty
import com.artemchep.keyguard.res.cipher_field_picker_items_title
import com.artemchep.keyguard.res.close
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun CipherFieldPickerScreen(
    args: CipherFieldPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherFieldPickerResult>,
) {
    val state = produceCipherFieldPickerState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        title = {
            CipherFieldPickerTitle(
                onBack = state.onBack,
            )
        },
        contentScrollable = false,
        content = {
            CipherFieldPickerContent(
                state = state,
            )
        },
        actions = {
            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            TextButton(
                enabled = updatedOnDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
private fun CipherFieldPickerTitle(
    onBack: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                )
            }
            Spacer(
                modifier = Modifier
                    .width(4.dp),
            )
        }
        val title = if (onBack != null) {
            Res.string.cipher_field_picker_fields_title
        } else {
            Res.string.cipher_field_picker_items_title
        }
        Text(stringResource(title))
    }
}

@Composable
private fun CipherFieldPickerContent(
    state: CipherFieldPickerState,
) {
    val getNavAnimation = koinInject<GetNavAnimation>()
    val updatedAnimationScale by rememberUpdatedState(LocalAnimationFactor)
    AnimatedContent(
        modifier = Modifier
            .fillMaxWidth(),
        targetState = state.step,
        contentKey = { step -> step::class },
        transitionSpec = {
            val transitionType = if (targetState is CipherFieldPickerState.Step.Fields) {
                NavigationAnimationType.GO_FORWARD
            } else {
                NavigationAnimationType.GO_BACKWARD
            }
            NavigationAnimation.transform(
                scale = updatedAnimationScale,
                animationType = getNavAnimation().value,
                transitionType = transitionType,
            )
        },
        label = "CipherFieldPickerStep",
    ) { step ->
        when (step) {
            is CipherFieldPickerState.Step.Items -> CipherLinkPickerItems(
                query = state.query,
                items = step.items,
                emptyText = stringResource(Res.string.cipher_field_picker_items_empty),
            )

            is CipherFieldPickerState.Step.Fields -> CipherFieldPickerFields(
                step = step,
            )
        }
    }
}

@Composable
private fun CipherFieldPickerFields(
    step: CipherFieldPickerState.Step.Fields,
) {
    // Both steps share one height, so the dialog does
    // not jump while drilling in.
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = CipherLinkPickerListMaxHeight),
    ) {
        item("item") {
            CompactVaultItem(
                item = step.item,
            )
        }
        item("item.spacer") {
            Spacer(Modifier.height(12.dp))
        }
        items(
            items = step.fields,
            key = { field -> field.id },
        ) { field ->
            FlatItemSimpleExpressive(
                shapeState = field.shapeState,
                leading = icon<RowScope>(
                    when (field.kind) {
                        CipherFieldPickerState.Field.Kind.Password -> Icons.Outlined.Key
                        CipherFieldPickerState.Field.Kind.Custom -> Icons.Outlined.TextFields
                    },
                ),
                title = {
                    Text(field.title)
                },
                text = {
                    Text(field.text)
                },
                onClick = field.onClick,
            )
        }
    }
}
