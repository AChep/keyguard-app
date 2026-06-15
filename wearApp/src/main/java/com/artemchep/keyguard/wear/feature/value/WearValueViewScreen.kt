package com.artemchep.keyguard.wear.feature.value

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.barcodetype.BarcodeImage
import com.artemchep.keyguard.feature.home.vault.component.rememberVisibilityState
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.wear.ui.WearContextAction
import com.artemchep.keyguard.wear.ui.WearDotsDivider
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val MinBarcodeSizePx = 129

@Composable
fun WearValueViewScreen(
    title: String?,
    value: String,
    visibility: Visibility,
    monospace: Boolean,
    colorize: Boolean,
    actions: List<ContextItem>,
) {
    OptionallyKeepScreenOnEffect()

    val contentColor = LocalContentColor.current
    val formattedValue = remember(value, colorize, contentColor) {
        if (colorize) {
            colorizePassword(
                password = value,
                contentColor = contentColor,
            )
        } else {
            AnnotatedString(value)
        }
    }
    val visibilityState = rememberVisibilityState(
        visibility,
    )
    val updatedVisibilityConfig by rememberUpdatedState(visibility)

    WearScaffoldScreen(
        title = title,
    ) { transformationSpec ->
        item("value") {
            WearValueViewValueCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                value = value,
                formattedValue = formattedValue,
                visibility = visibility,
                revealed = visibilityState.value.value,
                monospace = monospace,
                onRevealChange = { possibleNewValue ->
                    updatedVisibilityConfig.transformUserEvent(possibleNewValue) { newValue ->
                        visibilityState.value = Visibility.Event(
                            value = newValue,
                            timestamp = Clock.System.now(),
                        )
                    }
                },
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        item("qr.divider") {
            WearDotsDivider(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        item("qr") {
            WearValueViewQrCode(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                value = value,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        if (actions.isNotEmpty() && !isRelease) {
            item("actions.divider") {
                WearDotsDivider(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }

            itemsIndexed(
                items = actions,
                key = { index, _ -> "action.$index" },
            ) { _, action ->
                WearContextAction(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    item = action,
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }
}

@Composable
private fun WearValueViewQrCode(
    modifier: Modifier = Modifier,
    value: String,
    transformation: SurfaceTransformation? = null,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .surfaceTransformation(transformation),
        contentAlignment = Alignment.Center,
    ) {
        val size = maxWidth * 0.78f
        val imageSizePx = with(density) {
            size.roundToPx().coerceAtLeast(MinBarcodeSizePx)
        }
        val imageRequest = remember(value, imageSizePx) {
            BarcodeImageRequest(
                format = BarcodeImageFormat.QR_CODE,
                size = BarcodeImageRequest.Size(
                    width = imageSizePx,
                    height = imageSizePx,
                ),
                data = value,
            )
        }
        BarcodeImage(
            modifier = Modifier
                .size(size)
                .clip(MaterialTheme.shapes.large),
            imageModel = {
                imageRequest
            },
        )
    }
}

@Composable
private fun WearValueViewValueCard(
    modifier: Modifier = Modifier,
    value: String,
    formattedValue: AnnotatedString,
    visibility: Visibility,
    revealed: Boolean,
    monospace: Boolean,
    onRevealChange: (Boolean) -> Unit,
    transformation: SurfaceTransformation? = null,
) {
    val shownValue = animatedConcealedText(
        text = formattedValue,
        concealed = !revealed || visibility.hidden,
    )

    Card(
        modifier = modifier
            .heightIn(min = 16.dp),
        colors = CardDefaults.cardColors().run {
            copy(
                containerColor = Color.Transparent,
            )
        },
        transformation = transformation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .animateContentHeight()
                    .weight(1f),
            ) {
                val fontFamily = if (monospace) monoFontFamily else null
                if (value.isBlank() && !visibility.hidden) {
                    Text(
                        modifier = Modifier
                            .alpha(MediumEmphasisAlpha),
                        text = stringResource(Res.string.empty_value),
                        fontFamily = fontFamily,
                    )
                } else {
                    Text(
                        text = shownValue,
                        fontFamily = fontFamily,
                    )
                }
            }

            if (visibility.concealed && !visibility.hidden) {
                VisibilityToggle(
                    visible = revealed,
                    onVisibleChange = onRevealChange,
                )
            }
        }
    }
}
