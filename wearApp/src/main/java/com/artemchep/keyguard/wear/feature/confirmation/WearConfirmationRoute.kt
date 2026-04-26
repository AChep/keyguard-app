package com.artemchep.keyguard.wear.feature.confirmation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.confirmation.ConfirmationItemContent
import com.artemchep.keyguard.feature.confirmation.ConfirmationItemRenderers
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationState
import com.artemchep.keyguard.feature.confirmation.confirmationState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.learn_more
import com.artemchep.keyguard.res.ok
import com.artemchep.keyguard.res.pref_item_ssh_agent_status_unsupported
import com.artemchep.keyguard.res.select_file
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Stable
data class WearConfirmationRoute(
    val args: ConfirmationRoute.Args,
) : RouteForResult<ConfirmationResult> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<ConfirmationResult>,
    ) {
        WearConfirmationScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

@Composable
private fun WearConfirmationScreen(
    args: ConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<ConfirmationResult>,
) {
    val state = confirmationState(
        args = args,
        transmitter = transmitter,
    )
    WearScaffoldScreen(
        header = { transformationSpec ->
            ListHeader(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    args.icon?.invoke()
                    args.title?.let { title ->
                        Text(
                            text = title,
                            modifier = Modifier
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    args.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            modifier = Modifier
                                .fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        overlay = {
            WearScaffoldLoader(
                modifier = Modifier,
                visible = state.items is Loadable.Loading,
            )
        },
    ) { transformationSpec ->
        args.message?.let { message ->
            item("message") {
                WearListLabel(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    text = message,
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }

        when (state.items) {
            is Loadable.Loading -> {
                // Do nothing
            }

            is Loadable.Ok -> {
                state.items.getOrNull().orEmpty().forEach { item ->
                    item("item.${item.key}") {
                        WearConfirmationItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            item = item,
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
            }
        }

        item("confirm") {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                onClick = {
                    state.onConfirm?.invoke()
                },
                enabled = state.onConfirm != null,
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Text(text = stringResource(Res.string.ok))
            }
        }

        item("deny") {
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                onClick = {
                    state.onDeny?.invoke()
                },
                enabled = state.onDeny != null,
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Text(text = stringResource(Res.string.cancel))
            }
        }
    }
}

@Composable
private fun WearConfirmationItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item,
    transformation: SurfaceTransformation? = null,
) = ConfirmationItemContent(
    modifier = modifier,
    item = item,
    renderers = wearConfirmationItemRenderers(transformation),
)

private fun wearConfirmationItemRenderers(
    transformation: SurfaceTransformation?,
) = ConfirmationItemRenderers(
    booleanItem = { modifier, item ->
        WearConfirmationBooleanItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    },
    stringItem = { modifier, item ->
        WearConfirmationStringItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    },
    enumItem = { modifier, item ->
        WearConfirmationEnumItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    },
    fileItem = { modifier, item ->
        WearConfirmationFileItem(
            modifier = modifier,
            item = item,
            transformation = transformation,
        )
    },
)

@Composable
private fun WearConfirmationBooleanItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.BooleanItem,
    transformation: SurfaceTransformation? = null,
) {
    val onChange by rememberUpdatedState(item.onChange)
    SwitchButton(
        modifier = modifier,
        checked = item.value,
        onCheckedChange = {
            onChange(it)
        },
        enabled = item.enabled,
        label = {
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = item.text?.let { text ->
            {
                Text(
                    text = text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        transformation = transformation,
    )
}

@Composable
private fun WearConfirmationStringItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.StringItem,
    transformation: SurfaceTransformation? = null,
) {
    WearListCard(
        modifier = modifier,
        title = {
            Text(text = item.title)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = item.state.text,
                    onValueChange = { value ->
                        item.state.onChange?.invoke(value)
                    },
                    enabled = item.enabled,
                    singleLine = true,
                    label = {
                        Text(text = item.title)
                    },
                    supportingText = item.state.error?.let { error ->
                        {
                            Text(text = error)
                        }
                    },
                    textStyle = if (item.monospace) {
                        MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = monoFontFamily,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Default,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = if (item.password) {
                            KeyboardType.Password
                        } else {
                            KeyboardType.Text
                        },
                    ),
                )
                item.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        },
        transformation = transformation,
    )
}

@Composable
private fun WearConfirmationEnumItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.EnumItem,
    transformation: SurfaceTransformation? = null,
) {
    Column(
        modifier = modifier
            .surfaceTransformation(transformation),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.items.forEach { enumItem ->
            val updatedOnClick by rememberUpdatedState(enumItem.onClick)
            val text = buildString {
                append(enumItem.title)
                if (enumItem.selected) {
                    append(" ")
                    append(stringResource(Res.string.ok))
                }
            }
            val buttonModifier = Modifier.fillMaxWidth()
            if (enumItem.selected) {
                FilledTonalButton(
                    modifier = buttonModifier,
                    label = {
                        Text(
                            text = text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    secondaryLabel = enumItem.text?.let { secondary ->
                        {
                            Text(
                                text = secondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        updatedOnClick?.invoke()
                    },
                    enabled = item.enabled && updatedOnClick != null,
                )
            } else {
                OutlinedButton(
                    modifier = buttonModifier,
                    label = {
                        Text(
                            text = text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    secondaryLabel = enumItem.text?.let { secondary ->
                        {
                            Text(
                                text = secondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        updatedOnClick?.invoke()
                    },
                    enabled = item.enabled && updatedOnClick != null,
                    transformation = transformation,
                )
            }
        }

        item.doc?.let { doc ->
            WearListLabel(
                text = doc.text,
            )
        }
    }
}

@Composable
private fun WearConfirmationFileItem(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item.FileItem,
    transformation: SurfaceTransformation? = null,
) {
    WearListCard(
        modifier = modifier,
        title = {
            Text(text = item.title)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.value?.name ?: stringResource(Res.string.select_file),
                )
                WearListLabel(text = stringResource(Res.string.pref_item_ssh_agent_status_unsupported))
            }
        },
        trailing = {
            Checkbox(
                checked = false,
                enabled = false,
            )
        },
        transformation = transformation,
    )
}
