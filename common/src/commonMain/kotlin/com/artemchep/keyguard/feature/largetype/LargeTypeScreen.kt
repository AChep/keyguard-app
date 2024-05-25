package com.artemchep.keyguard.feature.largetype

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.KeepScreenOnEffect
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.colorizePasswordDigitColor
import com.artemchep.keyguard.ui.colorizePasswordSymbolColor
import com.artemchep.keyguard.ui.icons.KeyguardLargeType
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun LargeTypeScreen(
    args: LargeTypeRoute.Args,
) {
    val loadableState = produceLargeTypeScreenState(args)
    LargeTypeDialog(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LargeTypeDialog(
    loadableState: Loadable<LargeTypeState>,
) {
    KeepScreenOnEffect()

    Dialog(
        icon = icon(Icons.Outlined.KeyguardLargeType),
        title = {
            Text(stringResource(Res.string.largetype_title))
        },
        content = {
            val state = loadableState.getOrNull()
            if (state != null) {
                LargeTypeContent(
                    state = state,
                )
            }
        },
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onClose)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LargeTypeContent(
    state: LargeTypeState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        val elevation = LocalAbsoluteTonalElevation.current
        val indexState = animateIntAsState(state.index)
        FlowRow(
            modifier = Modifier
                .padding(
                    horizontal = 16.dp,
                )
                .align(Alignment.Start),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.groups.forEach { groups ->
                FlowRow(
                    modifier = Modifier,
                ) {
                    groups.forEach { item ->
                        SymbolItem(
                            item = item,
                            elevation = elevation,
                            selectedIndex = indexState,
                        )
                    }
                }
            }
        }
        ExpandedIfNotEmpty(
            valueOrNull = state.text,
        ) { text ->
            Text(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(horizontal = Dimens.horizontalPadding),
                text = text,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SymbolItem(
    modifier: Modifier = Modifier,
    item: LargeTypeState.Item,
    selectedIndex: State<Int>,
    elevation: Dp,
) {
    val index = item.index

    val contentModifier = run {
        val selected by remember(index, selectedIndex) {
            derivedStateOf {
                selectedIndex.value >= index
            }
        }

        val targetBackgroundColor = if (selected) {
            MaterialTheme.colorScheme.selectedContainer
        } else {
            val isEven = index.rem(2) == 0
            val backgroundElevation = if (isEven) {
                elevation.plus(8.dp)
            } else {
                elevation.minus(2.dp).coerceAtLeast(0.dp)
            }
            MaterialTheme.colorScheme
                .surfaceColorAtElevation(backgroundElevation)
        }
        val backgroundColorState = animateColorAsState(targetBackgroundColor)
        Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .drawBehind {
                drawRect(backgroundColorState.value)
            }
    }

    val updatedOnClick by rememberUpdatedState(item.onClick)
    Column(
        modifier = modifier
            .then(contentModifier)
            .clickable {
                updatedOnClick?.invoke()
            }
            .padding(
                horizontal = 6.dp,
                vertical = 8.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val color = LocalContentColor.current
        val text = remember(color, item) {
            buildAnnotatedString {
                val symbolColor = when {
                    !item.colorize || item.text.length > 1 -> color
                    item.text[0].isDigit() -> colorizePasswordDigitColor(color)
                    item.text[0].isLetter() -> color
                    else -> colorizePasswordSymbolColor(color)
                }
                withStyle(
                    style = SpanStyle(
                        color = symbolColor,
                    ),
                ) {
                    append(item.text)
                }
            }
        }
        Text(
            text = text,
            fontFamily = monoFontFamily,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = index.plus(1).toString(),
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
