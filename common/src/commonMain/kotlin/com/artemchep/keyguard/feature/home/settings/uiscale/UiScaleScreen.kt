package com.artemchep.keyguard.feature.home.settings.uiscale

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Label
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.UI_SCALE_DEFAULT
import com.artemchep.keyguard.common.model.UI_SCALE_MAX_STEP
import com.artemchep.keyguard.common.model.formatUiScale
import com.artemchep.keyguard.common.model.uiScaleFromStep
import com.artemchep.keyguard.common.model.uiScaleToStep
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.FlatSurfaceExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.pref_item_ui_scale_decrease
import com.artemchep.keyguard.res.pref_item_ui_scale_increase
import com.artemchep.keyguard.res.pref_item_ui_scale_preview_default
import com.artemchep.keyguard.res.pref_item_ui_scale_preview_selected
import com.artemchep.keyguard.res.pref_item_ui_scale_preview_text
import com.artemchep.keyguard.res.pref_item_ui_scale_title
import com.artemchep.keyguard.res.save
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

@Composable
fun UiScaleScreen() {
    val state = produceUiScaleState()
    if (state is Loadable.Ok) {
        UiScaleContent(state.value)
    }
}

@Composable
private fun UiScaleContent(state: UiScaleState) {
    Dialog(
        icon = { Icon(imageVector = Icons.Outlined.ZoomIn, contentDescription = null) },
        title = { Text(stringResource(Res.string.pref_item_ui_scale_title)) },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UiScaleValue(
                    modifier = Modifier
                        .fillMaxWidth(),
                    step = state.step,
                )
                UiScaleControl(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    state = state,
                )
                UiScalePreview(
                    state = state,
                )
            }
        },
        actions = {
            TextButton(onClick = state.onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                enabled = state.canSave,
                onClick = state.onSave,
            ) {
                Text(stringResource(Res.string.save))
            }
        },
    )
}

@Composable
private fun UiScaleValue(
    modifier: Modifier = Modifier,
    step: Int,
) {
    AnimatedContent(
        modifier = modifier,
        targetState = step,
        contentAlignment = Alignment.Center,
    ) { s ->
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = formatUiScale(s),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UiScaleControl(
    modifier: Modifier = Modifier,
    state: UiScaleState,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            shapes = IconButtonDefaults.shapes(),
            enabled = state.step > 0,
            onClick = {
                state.onStepChange(state.step - 1)
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Remove,
                contentDescription = stringResource(Res.string.pref_item_ui_scale_decrease),
            )
        }
        UiScaleSlider(
            modifier = Modifier
                .weight(1f),
            step = state.step,
            onStepChange = state.onStepChange,
        )
        FilledTonalIconButton(
            shapes = IconButtonDefaults.shapes(),
            enabled = state.step < UI_SCALE_MAX_STEP,
            onClick = {
                state.onStepChange(state.step + 1)
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(Res.string.pref_item_ui_scale_increase),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UiScaleSlider(
    modifier: Modifier = Modifier,
    step: Int,
    onStepChange: (Int) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val sliderState = remember {
        SliderState(
            step.toFloat(),
            UI_SCALE_MAX_STEP - 1,
            0f..UI_SCALE_MAX_STEP.toFloat(),
        )
    }
    // The buttons change the step from the outside.
    LaunchedEffect(step) {
        if (sliderState.value.roundToInt() != step) {
            sliderState.value = step.toFloat()
        }
    }
    val updatedOnStepChange by rememberUpdatedState(onStepChange)
    val valueText = formatUiScale(step)
    Slider(
        modifier = modifier
            .semantics {
                stateDescription = valueText
            },
        state = sliderState,
        onValueChange = { value ->
            sliderState.value = value
            updatedOnStepChange(value.roundToInt())
        },
        interactionSource = interactionSource,
        thumb = {
            Label(
                label = {
                    PlainTooltip {
                        Text(valueText)
                    }
                },
                interactionSource = interactionSource,
            ) {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                )
            }
        },
    )
}

@Composable
private fun UiScalePreview(
    state: UiScaleState,
) {
    Column {
        UiScalePreviewItem(
            title = stringResource(Res.string.pref_item_ui_scale_preview_default),
            step = uiScaleToStep(UI_SCALE_DEFAULT),
            appliedScale = state.appliedScale,
            shapeState = ShapeState.START,
        )
        UiScalePreviewItem(
            title = stringResource(Res.string.pref_item_ui_scale_preview_selected),
            step = state.step,
            appliedScale = state.appliedScale,
            shapeState = ShapeState.END,
        )
    }
}

@Composable
private fun UiScalePreviewItem(
    title: String,
    step: Int,
    appliedScale: Float,
    shapeState: Int,
) {
    FlatSurfaceExpressive(
        shapeState = shapeState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.contentPadding,
                    vertical = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val labelColor = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha)
                Text(
                    modifier = Modifier
                        .weight(1f),
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                )
                Text(
                    text = formatUiScale(step),
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                )
            }
            // The dialog is already drawn at the applied scale, so
            // the sample has to undo it to show the real target size.
            val density = LocalDensity.current
            val sampleDensity = remember(density, step, appliedScale) {
                val ratio = uiScaleFromStep(step) / appliedScale
                Density(
                    density = density.density * ratio,
                    fontScale = density.fontScale,
                )
            }
            CompositionLocalProvider(
                LocalDensity provides sampleDensity,
            ) {
                Text(
                    text = stringResource(Res.string.pref_item_ui_scale_preview_text),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
