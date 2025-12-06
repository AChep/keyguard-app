package com.artemchep.keyguard.feature.confirmation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.search.filter.component.FilterItemComposable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordStrengthBadge
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConfirmationScreen(
    args: ConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<ConfirmationResult>,
) {
    val state = confirmationState(
        args = args,
        transmitter = transmitter,
    )

    FilePickerEffect(
        flow = state.sideEffects.filePickerIntentFlow,
    )

    val showContent = args.message != null || args.items.isNotEmpty()
    Dialog(
        icon = args.icon,
        title = if (args.title != null) {
            // composable
            {
                Text(
                    text = args.title,
                )
                if (args.subtitle != null) {
                    Text(
                        text = args.subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        } else {
            null
        },
        content = if (showContent) {
            // composable
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    val items = state.items.getOrNull().orEmpty()
                    if (args.message != null) {
                        Text(
                            modifier = Modifier
                                .padding(horizontal = Dimens.horizontalPadding),
                            text = args.message,
                        )
                        if (items.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items.forEach { item ->
                            key(item.key) {
                                val itemModifier = if (item.enabled) {
                                    Modifier
                                } else {
                                    Modifier
                                        .alpha(DisabledEmphasisAlpha)
                                }
                                ConfirmationItem(
                                    modifier = itemModifier,
                                    item = item,
                                )
                            }
                        }
                    }

                    if (state.items is Loadable.Loading) {
                        SkeletonItem()
                    }
                }
            }
        } else {
            null
        },
        actions = {
            if (args.docUrl != null) {
                val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
                val updatedDocUrl by rememberUpdatedState(args.docUrl)
                TextButton(
                    modifier = Modifier,
                    onClick = {
                        val intent = NavigationIntent.NavigateToBrowser(updatedDocUrl)
                        updatedNavigationController.queue(intent)
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.uri_action_launch_docs_title),
                    )
                }
                Spacer(
                    modifier = Modifier
                        .weight(1f),
                )
            }

            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            val updatedOnConfirm by rememberUpdatedState(state.onConfirm)
            TextButton(
                enabled = state.onDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                enabled = state.onConfirm != null,
                onClick = {
                    updatedOnConfirm?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
private fun ConfirmationItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item,
) = when (item) {
    is ConfirmationState.Item.BooleanItem -> ConfirmationBooleanItem(
        modifier = modifier,
        item = item,
    )

    is ConfirmationState.Item.StringItem -> ConfirmationStringItem(
        modifier = modifier,
        item = item,
    )

    is ConfirmationState.Item.EnumItem -> ConfirmationEnumItem(
        modifier = modifier,
        item = item,
    )

    is ConfirmationState.Item.FileItem -> ConfirmationFileItem(
        modifier = modifier,
        item = item,
    )
}

@Composable
private fun ConfirmationBooleanItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.BooleanItem,
) {
    FlatItemLayout(
        modifier = modifier,
        leading = {
            Checkbox(
                checked = item.value,
                onCheckedChange = null,
            )
        },
        content = {
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (item.text != null) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        },
        onClick = {
            val newValue = !item.value
            item.onChange.invoke(newValue)
        },
    )
}

@Composable
private fun ConfirmationStringItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.StringItem,
) {
    val visibilityState = remember(item.sensitive) {
        VisibilityState(
            isVisible = !item.sensitive,
        )
    }
    Column(
        modifier = modifier,
    ) {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            label = item.title,
            value = item.state,
            textStyle = when {
                item.monospace ->
                    TextStyle(
                        fontFamily = monoFontFamily,
                    )

                else -> LocalTextStyle.current
            },
            visualTransformation = if (visibilityState.isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = when {
                item.password ->
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    )

                else -> KeyboardOptions.Default
            },
            singleLine = true,
            maxLines = 1,
            trailing = {
                ExpandedIfNotEmptyForRow(
                    Unit.takeIf { item.sensitive },
                ) {
                    VisibilityToggle(
                        visibilityState = visibilityState,
                    )
                }
                ExpandedIfNotEmptyForRow(
                    item.generator,
                ) { generator ->
                    val key = when (generator) {
                        ConfirmationState.Item.StringItem.Generator.Username -> "username"
                        ConfirmationState.Item.StringItem.Generator.Password -> "password"
                    }
                    AutofillButton(
                        key = key,
                        username = generator == ConfirmationState.Item.StringItem.Generator.Username,
                        password = generator == ConfirmationState.Item.StringItem.Generator.Password,
                        onValueChange = item.state.onChange,
                    )
                }
            },
            content = {
                ExpandedIfNotEmpty(
                    valueOrNull = Unit
                        .takeIf {
                            item.value.isNotEmpty() &&
                                    item.password &&
                                    item.state.error == null
                        },
                ) {
                    PasswordStrengthBadge(
                        modifier = Modifier
                            .padding(
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        password = item.value,
                    )
                }
            },
        )
        if (item.description != null) {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.horizontalPadding,
                        vertical = 8.dp,
                    ),
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmationEnumItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.EnumItem,
) {
    Column(
        modifier = modifier,
    ) {
        FlowRow(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.items.forEach { item ->
                key(item.key) {
                    ConfirmationEnumItemItem(
                        modifier = Modifier,
                        item = item,
                    )
                }
            }
        }
        ExpandedIfNotEmpty(
            valueOrNull = item.doc,
        ) { doc ->
            Column {
                Spacer(
                    modifier = Modifier
                        .height(32.dp),
                )
                Icon(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
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
                        .padding(horizontal = Dimens.horizontalPadding)
                        .animateContentSize(),
                    text = doc.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
                )
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                val updatedOnLearnMore by rememberUpdatedState(doc.onLearnMore)
                TextButton(
                    modifier = Modifier
                        .padding(horizontal = 12.dp),
                    enabled = updatedOnLearnMore != null,
                    onClick = {
                        updatedOnLearnMore?.invoke()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.learn_more),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationEnumItemItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.EnumItem.Item,
) {
    FilterItemComposable(
        modifier = modifier,
        checked = item.selected,
        leading =
            if (item.icon != null) {
                // composable
                {
                    Icon(
                        item.icon,
                        null,
                    )
                }
            } else {
                null
            },
        title = item.title,
        text = item.text,
        onClick = item.onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmationFileItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.FileItem,
) {
    FlatItemLayout(
        modifier = modifier,
        leading = {
            val colorTarget = if (item.valid) {
                LocalContentColor.current
            } else {
                MaterialTheme.colorScheme.error
            }
            val colorState = animateColorAsState(colorTarget)
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = colorState.value,
            )
        },
        content = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
            )

            val file = item.value
            if (file != null) {
                val name = file.name ?: file.uri.substringAfterLast('/')
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f, fill = false),
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val size = remember() {
                        file.size?.let(::humanReadableByteCountSI)
                    }
                    if (size != null) {
                        Text(
                            text = size,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(Res.string.select_file),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        },
        trailing = {
            ExpandedIfNotEmptyForRow(item.onClear) { onClear ->
                IconButton(
                    onClick = onClear,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = null,
                    )
                }
            }
        },
        onClick = item.onSelect,
    )
}
