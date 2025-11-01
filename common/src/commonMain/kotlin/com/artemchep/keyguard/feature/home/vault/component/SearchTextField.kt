package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.feature.PromoView
import com.artemchep.keyguard.feature.keyguard.setup.keyguardSpan
import com.artemchep.keyguard.feature.rememberPromoViewStatus
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.PlainTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.pluralStringResource

@Composable
fun SearchTextField(
    modifier: Modifier = Modifier,
    text: String,
    placeholder: String,
    searchIcon: Boolean = true,
    focusRequester: FocusRequester2,
    focusFlow: Flow<Unit>?,
    count: Int? = null,
    playPromo: Boolean = false,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onTextChange: ((String) -> Unit)?,
) {
    val promoState = rememberPromoViewStatus(
        playPromo = playPromo,
        ready = onTextChange != null,
    )

    val interactionSource = remember {
        MutableInteractionSource()
    }

    LaunchedEffect(focusFlow) {
        focusFlow
            ?: return@LaunchedEffect
        focusFlow.collect {
            focusRequester.requestFocus(showKeyboard = true)
        }
    }

    val isEmptyState = rememberUpdatedState(text.isEmpty())
    val isFocusedState = interactionSource.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester2(focusRequester),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                // Height should be the size of
                // the Material 3 top bar.
                .height(64.dp)
                .padding(
                    horizontal = Dimens.contentPadding,
                    vertical = 8.dp,
                )
                .searchTextFieldBackground(
                    isEmptyState = isEmptyState,
                    isFocusedState = isFocusedState,
                    shape = CircleShape,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .size(8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()

                if (searchIcon) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                    )
                } else {
                    Spacer(
                        modifier = Modifier
                            .size(16.dp),
                    )
                }
                Spacer(
                    modifier = Modifier
                        .size(16.dp),
                )
            }
            val textStyle = TextStyle(
                fontSize = 20.sp,
            )
            val updatedOnChange by rememberUpdatedState(onTextChange)
            PlainTextField(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    // When focused, clicking the Escape key should
                    // clear the text field.
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                            updatedOnChange?.invoke("")
                            return@onKeyEvent true
                        }

                        false
                    },
                interactionSource = interactionSource,
                value = text,
                textStyle = textStyle,
                placeholder = {
                    PromoView(
                        state = promoState,
                        promo = {
                            val promo = remember {
                                keyguardSpan()
                            }
                            Text(
                                text = promo,
                                style = textStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    ) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                enabled = onTextChange != null,
                onValueChange = {
                    updatedOnChange?.invoke(it)
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                singleLine = true,
            )
            if (count != null) {
                val resultsCount = pluralStringResource(
                    Res.plurals.result_count_plural,
                    count,
                    count.toString(),
                )
                Text(
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    text = resultsCount,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha),
                    maxLines = 2,
                )
            }
            Spacer(
                modifier = Modifier
                    .size(8.dp),
            )
            ExpandedIfNotEmptyForRow(
                valueOrNull = Unit
                    .takeIf { !isEmptyState.value },
            ) {
                IconButton(
                    enabled = onTextChange != null,
                    onClick = {
                        updatedOnChange?.invoke("")
                    },
                ) {
                    Icon(Icons.Outlined.Clear, null)
                }
            }
        }

        trailing()
    }
}

private fun Modifier.searchTextFieldBackground(
    isEmptyState: State<Boolean>,
    isFocusedState: State<Boolean>,
    shape: Shape,
): Modifier = composed {
    val isHighlightedState = remember(isEmptyState, isFocusedState) {
        derivedStateOf {
            !isEmptyState.value ||
                    isFocusedState.value
        }
    }

    val colorAlphaTarget: Float
    val colorRgbTarget: Color

    val backgroundColor = MaterialTheme.colorScheme.background
    val luminance = backgroundColor.luminance()
    if (luminance > 0.5f) { // light background
        colorAlphaTarget = 0.07f
        colorRgbTarget = Color.Black
    } else if (luminance > 0f) { // dark background
        colorAlphaTarget = 0.22f
        colorRgbTarget = Color.Black
    } else { // black background
        colorAlphaTarget = 0.14f
        colorRgbTarget = Color.Gray
    }

    val colorTarget = colorRgbTarget
        .combineAlpha(colorAlphaTarget)
    val colorState = animateColorAsState(
        targetValue = if (isHighlightedState.value) colorTarget else colorTarget.combineAlpha(0f),
        label = "SearchFieldBackground",
    )

    this
        .clip(shape)
        .drawBehind {
            val color = colorState.value
            drawRect(color)
        }
}
