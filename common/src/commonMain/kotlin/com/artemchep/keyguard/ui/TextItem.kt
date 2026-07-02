package com.artemchep.keyguard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.component.surfaceShape
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.platform.input.IncognitoInput
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.bringIntoView
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.util.DividerColor
import com.artemchep.keyguard.ui.util.HorizontalDivider
import com.artemchep.keyguard.ui.util.VerticalDivider
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

const val PLACEHOLDER_EMAIL = "username@example.com"

@Composable
fun UrlFlatTextField(
    modifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = null,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = if (defaultLeading()) {
        // composable
        {
            IconBox(
                main = Icons.Outlined.KeyguardWebsite,
            )
        }
    } else {
        null
    },
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    FlatTextField(
        modifier = modifier,
        testTag = testTag,
        label = label
            ?: stringResource(Res.string.url),
        placeholder = placeholder,
        value = value,
        shapeState = shapeState,
        expressive = expressive,
        keyboardOptions = keyboardOptions.copy(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
        ),
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        clearButton = clearButton,
        leading = leading,
        trailing = trailing,
        content = content,
    )
}

@Composable
fun TagFlatTextField(
    modifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = null,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = if (defaultLeading()) {
        // composable
        {
            IconBox(
                main = Icons.AutoMirrored.Outlined.Label,
            )
        }
    } else {
        null
    },
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    FlatTextField(
        modifier = modifier,
        testTag = testTag,
        label = label
            ?: stringResource(Res.string.tag),
        placeholder = placeholder,
        value = value,
        shapeState = shapeState,
        expressive = expressive,
        keyboardOptions = keyboardOptions.copy(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
        ),
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        clearButton = clearButton,
        leading = leading,
        trailing = trailing,
        content = content,
    )
}

@Composable
fun EmailFlatTextField(
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = PLACEHOLDER_EMAIL,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = if (defaultLeading()) {
        // composable
        {
            IconBox(
                main = Icons.Outlined.Email,
            )
        }
    } else {
        null
    },
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    FlatTextField(
        modifier = modifier,
        fieldModifier = fieldModifier,
        boxModifier = boxModifier,
        testTag = testTag,
        label = label
            ?: stringResource(Res.string.email),
        placeholder = placeholder,
        value = value,
        shapeState = shapeState,
        expressive = expressive,
        keyboardOptions = keyboardOptions.copy(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
        ),
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        clearButton = clearButton,
        leading = leading,
        trailing = trailing,
        content = content,
    )
}

@Composable
fun PasswordFlatTextField(
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = null,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = if (defaultLeading()) {
        // composable
        {
            IconBox(
                main = Icons.Outlined.Password,
            )
        }
    } else {
        null
    },
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ConcealedFlatTextField(
        modifier = modifier,
        fieldModifier = fieldModifier,
        boxModifier = boxModifier,
        testTag = testTag,
        label = label
            ?: stringResource(Res.string.password),
        placeholder = placeholder,
        value = value,
        shapeState = shapeState,
        expressive = expressive,
        keyboardOptions = keyboardOptions.copy(
            autoCorrectEnabled = false,
            keyboardType = keyboardOptions.keyboardType
                .takeIf { it != KeyboardType.Unspecified }
                ?: KeyboardType.Password,
        ),
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        clearButton = clearButton,
        leading = leading,
        trailing = trailing,
        content = content,
    )
}

@Composable
fun ConcealedFlatTextField(
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = null,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val visibilityState = remember {
        VisibilityState()
    }
    val passwordVisualTransformation = remember {
        PasswordVisualTransformation()
    }
    FlatTextField(
        modifier = modifier,
        fieldModifier = fieldModifier,
        boxModifier = boxModifier,
        testTag = testTag,
        label = label,
        placeholder = placeholder,
        value = value,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = monoFontFamily,
        ),
        visualTransformation = if (visibilityState.isVisible) {
            VisualTransformation.None
        } else {
            passwordVisualTransformation
        },
        shapeState = shapeState,
        expressive = expressive,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        interactionSource = interactionSource,
        clearButton = clearButton,
        leading = leading,
        trailing = {
            VisibilityToggle(
                visibilityState = visibilityState,
            )
            if (trailing != null) {
                trailing()
            }
        },
        content = content,
        incognito = true,
    )
}

@Composable
fun FakeFlatTextField(
    modifier: Modifier = Modifier,
    label: String? = null,
    error: String? = null,
    value: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    onClear: (() -> Unit)?,
    textStyle: TextStyle = LocalTextStyle.current,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val isError = error != null
    var hasFocus by remember {
        mutableStateOf(false)
    }

    val updatedOnClick by rememberUpdatedState(onClick)
    FlatTextFieldSurface(
        modifier = modifier
            .onFocusChanged { state ->
                hasFocus = state.hasFocus
            },
        isError = isError,
        isFocused = hasFocus,
        shapeState = shapeState,
        expressive = expressive,
    ) {
        Column(
            modifier = Modifier
                .clickable(
                    indication = LocalIndication.current,
                    enabled = onClick != null,
                    interactionSource = remember {
                        MutableInteractionSource()
                    },
                    role = Role.Button,
                ) {
                    updatedOnClick?.invoke()
                },
        ) {
            Row(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val disabledAlphaTarget = if (onClick != null) 1f else DisabledEmphasisAlpha
                val disabledAlphaState = animateFloatAsState(disabledAlphaTarget)

                if (leading != null) {
                    Row(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = disabledAlphaState.value
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading()
                    }
                    Spacer(
                        modifier = Modifier
                            .width(16.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    val expanded = onClear != null
                    TextFieldLabelLayout(
                        modifier = Modifier
                            .heightIn(min = 50.dp),
                        expanded = onClear != null,
                    ) {
                        if (label != null) {
                            TextFieldLabel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .graphicsLayer {
                                        alpha = disabledAlphaState.value
                                    },
                                text = label,
                                expanded = expanded,
                                hasFocus = hasFocus,
                                hasError = isError,
                            )
                        }

                        Box {
                            androidx.compose.animation.AnimatedVisibility(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                enter = fadeIn(),
                                exit = fadeOut(),
                                visible = expanded,
                            ) {
                                // If color is not provided via the text style, use content color as a default
                                val textColor = run {
                                    val color =
                                        contentColorFor(backgroundColor = MaterialTheme.colorScheme.background)
                                    val alpha =
                                        if (onClick != null) DefaultEmphasisAlpha else DisabledEmphasisAlpha
                                    color.copy(alpha = alpha)
                                }
                                val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))
                                ProvideTextStyle(mergedTextStyle) {
                                    value()
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .padding(
                                end = 8.dp,
                            ),
                    ) {
                        ExpandedIfNotEmpty(
                            valueOrNull = error,
                        ) { text ->
                            FlatTextFieldBadgeLegacy(
                                error = text,
                                badge = null,
                            )
                        }
                        if (content != null) {
                            content()
                        }
                    }
                }
                if (trailing != null) {
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = disabledAlphaState.value
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        trailing()
                    }
                }
                ExpandedIfNotEmptyForRow(
                    valueOrNull = onClear,
                ) { lambda ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .height(24.dp),
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        IconButton(
                            onClick = lambda,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Owns the local edit buffer of a text field. Typing binds synchronously
 * to the returned state; the buffer adopts [text] only when
 * [textRevision] changes (a programmatic write — clear, prefill,
 * generated value), placing the caret at the end. Echoes of the user's
 * own keystrokes keep the revision and never touch the buffer.
 */
@Composable
fun rememberFieldBuffer(
    text: String,
    textRevision: Int,
): MutableState<TextFieldValue> {
    val buffer = remember {
        mutableStateOf(
            TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            ),
        )
    }
    LaunchedEffect(textRevision) {
        // No-op on the initial run (the buffer seeds from the model);
        // adopts on every revision bump afterwards.
        if (text != buffer.value.text) {
            buffer.value = TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            )
        }
    }
    return buffer
}

@Composable
fun FlatTextField(
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    focusRequester: FocusRequester2? = null,
    testTag: String? = null,
    label: String? = null,
    placeholder: String? = null,
    value: TextFieldModel,
    textStyle: TextStyle = LocalTextStyle.current,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    incognito: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    clearButton: Boolean = defaultClearButton(),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val enabled = value.onChange != null
    val fieldBuffer = rememberFieldBuffer(
        text = value.text,
        textRevision = value.textRevision,
    )
    val updatedOnChange by rememberUpdatedState(value.onChange)

    fun updateFieldValue(next: TextFieldValue) {
        fieldBuffer.value = next
        updatedOnChange?.invoke(next.text)
    }

    val isError = value.error != null
    var hasFocus by remember {
        mutableStateOf(false)
    }

    val fieldFocusRequester = focusRequester ?: remember {
        FocusRequester2()
    }

    FlatTextFieldSurface(
        modifier = modifier
            .onFocusChanged { state ->
                hasFocus = state.hasFocus
            },
        isError = isError,
        isFocused = hasFocus,
        shapeState = shapeState,
        expressive = expressive,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val disabledAlphaTarget = if (value.onChange != null) 1f else DisabledEmphasisAlpha
                val disabledAlphaState = animateFloatAsState(disabledAlphaTarget)

                if (leading != null) {
                    Row(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = disabledAlphaState.value
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading()
                    }
                    Spacer(
                        modifier = Modifier
                            .width(16.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    val focused = hasFocus || fieldBuffer.value.text.isNotEmpty()
                    TextFieldLabelLayout(
                        modifier = Modifier
                            .heightIn(min = 50.dp),
                        expanded = focused,
                    ) {
                        if (label != null) {
                            TextFieldLabel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .graphicsLayer {
                                        alpha = disabledAlphaState.value
                                    },
                                text = label,
                                expanded = focused,
                                hasFocus = hasFocus,
                                hasError = isError,
                            )
                        }

                        val finalPlaceholder = placeholder ?: value.hint
                        PlainTextField(
                            modifier = fieldModifier
                                .fillMaxWidth()
                                .focusRequester2(fieldFocusRequester),
                            boxModifier = boxModifier,
                            placeholder = if (finalPlaceholder != null) {
                                // composable
                                {
                                    val ff by animateFloatAsState(
                                        targetValue = if (focused || label == null) 1f else 0f,
                                    )
                                    Text(
                                        modifier = Modifier
                                            .alpha(ff),
                                        text = finalPlaceholder,
                                        maxLines = 1,
                                    )
                                }
                            } else {
                                null
                            },
                            textStyle = textStyle,
                            visualTransformation = visualTransformation,
                            keyboardOptions = keyboardOptions,
                            keyboardActions = keyboardActions,
                            singleLine = singleLine,
                            maxLines = maxLines,
                            value = fieldBuffer.value,
                            testTag = testTag,
                            enabled = enabled,
                            isError = value.error != null,
                            interactionSource = interactionSource,
                            incognito = incognito,
                            onValueChange = ::updateFieldValue,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .padding(
                                end = 8.dp,
                            ),
                    ) {
                        ExpandedIfNotEmpty(
                            valueOrNull = value.error ?: value.vl,
                        ) {
                            val error = it as? String
                            val badge = it as? TextFieldModel.Vl
                            FlatTextFieldBadgeLegacy(
                                error = error,
                                badge = badge,
                            )
                        }
                        if (content != null) {
                            content()
                        }
                    }
                }
                if (trailing != null) {
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = disabledAlphaState.value
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        trailing()
                    }
                }
                val isNotEmpty = fieldBuffer.value.text.isNotEmpty()
                ExpandedIfNotEmptyForRow(
                    valueOrNull = Unit.takeIf { isNotEmpty && hasFocus && clearButton },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .height(24.dp),
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        IconButton(
                            enabled = value.onChange != null,
                            onClick = {
                                updateFieldValue(TextFieldValue(""))
                                // After clearing the text field we want to focus
                                // it, so the focus doesn't get reset to start of
                                // the page.
                                fieldFocusRequester.requestFocus()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            val optionsText = rememberUpdatedState(fieldBuffer.value.text)
            val optionsList = rememberUpdatedState(value.autocompleteOptions)
            if (optionsList.value.isEmpty()) {
                return@Column
            }

            val options by remember {
                val valueFlow = snapshotFlow { optionsText.value }
                    .debounce(80L)
                    .distinctUntilChanged()
                val optionsFlow = snapshotFlow { optionsList.value }
                combine(
                    valueFlow,
                    optionsFlow,
                ) { v, options ->
                    val filteredOptions = options
                        .asSequence()
                        .filter { it.contains(v, ignoreCase = true) }
                        .take(3)
                        .toImmutableList()
                    // If the only option is the exact match of the value,
                    // then we do not show any suggestion.
                    if (filteredOptions.size == 1) {
                        val option = filteredOptions.first()
                        if (option == v) {
                            return@combine persistentListOf()
                        }
                    }

                    filteredOptions
                }
                    .map { options ->
                        Afh(
                            options = options,
                            createdAt = Clock.System.now(),
                        )
                    }
                    .flowOn(Dispatchers.Default)
            }.collectAsState(
                initial = Afh(
                    options = persistentListOf(),
                    createdAt = Clock.System.now(),
                ),
            )
            ExpandedIfNotEmpty(
                Unit.takeIf { hasFocus && options.options.isNotEmpty() },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(
                            bottom = 8.dp,
                            start = 8.dp,
                            end = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Icon(
                        modifier = Modifier
                            .size(18.dp),
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    options.options.forEachIndexed { index, text ->
                        if (index > 0) {
                            Spacer(
                                modifier = Modifier
                                    .width(8.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clip(MaterialTheme.shapes.small)
                                .border(Dp.Hairline, DividerColor, MaterialTheme.shapes.small)
                                .clickable {
                                    updateFieldValue(
                                        TextFieldValue(
                                            text = text,
                                            selection = TextRange(text.length),
                                        ),
                                    )
                                }
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Afh(
    val options: ImmutableList<String>,
    val createdAt: Instant,
)

@Composable
private fun TextFieldLabel(
    modifier: Modifier = Modifier,
    text: String,
    expanded: Boolean,
    hasFocus: Boolean,
    hasError: Boolean,
) {
    val expandedTextSize = MaterialTheme.typography.bodySmall.fontSize.value
    val normalTextSize = LocalTextStyle.current.fontSize.value
    val textSize by animateFloatAsState(
        targetValue = if (expanded) expandedTextSize else normalTextSize,
    )

    val expandedTextColor = when {
        hasError -> MaterialTheme.colorScheme.error
        hasFocus -> MaterialTheme.colorScheme.primary
        else -> LocalContentColor.current
    }
    val normalTextColor = LocalContentColor.current
        .combineAlpha(HighEmphasisAlpha)
    val textColor by animateColorAsState(
        targetValue = if (expanded) expandedTextColor else normalTextColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Text(
        modifier = modifier,
        text = text,
        fontSize = textSize.sp,
        maxLines = 1,
        color = textColor,
    )
}

@Composable
private fun TextFieldLabelLayout(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Layout(
            content = content,
        ) { measurables, constraints ->
            val itemConstraints = constraints.copy(
                minHeight = 0,
            )
            val placeables = measurables
                .map { measurable ->
                    measurable.measure(itemConstraints)
                }

            val width = constraints.maxWidth
            val height = kotlin.run {
                val maxHeight = placeables
                    .maxOf { it.height }
                if (placeables.size == 2) {
                    val (label, field) = placeables
                    val height = (label.height * progress).roundToInt() + field.height
                    max(height, maxHeight)
                } else {
                    maxHeight
                }
            }
            layout(width, height) {
                if (placeables.size == 2) {
                    val (label, field) = placeables
                    label.placeRelative(0, 0)
                    field.placeRelative(0, (label.height * progress).roundToInt())
                } else {
                    placeables.first()
                        .placeRelative(0, 0)
                }
            }
        }
    }
}

@Composable
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    testTag: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    incognito: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // If color is not provided via the text style, use content color as a default
    val textColor = run {
        val color = contentColorFor(backgroundColor = MaterialTheme.colorScheme.background)
        val alpha = if (enabled) DefaultEmphasisAlpha else DisabledEmphasisAlpha
        color.copy(alpha = alpha)
    }
    val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))
    val cursorBrushColor =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    IncognitoInputIf(
        enabled = incognito || keyboardOptions.isPasswordInput(),
    ) {
        BasicTextField(
            value = value,
            modifier = modifier
                .then(
                    if (testTag != null) {
                        Modifier.testTag(testTag)
                    } else {
                        Modifier
                    },
                )
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                )
                .bringIntoView(),
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(cursorBrushColor),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            decorationBox = @Composable { innerTextField ->
                PlainTextFieldDecorationBox(
                    modifier = boxModifier,
                    value = value,
                    innerTextField = innerTextField,
                    placeholder = placeholder,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                )
            },
        )
    }
}

@Composable
fun PlainTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    testTag: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    incognito: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // If color is not provided via the text style, use content color as a default
    val textColor = run {
        val color = contentColorFor(backgroundColor = MaterialTheme.colorScheme.background)
        val alpha = if (enabled) DefaultEmphasisAlpha else DisabledEmphasisAlpha
        color.copy(alpha = alpha)
    }
    val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))
    val cursorBrushColor =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    IncognitoInputIf(
        enabled = incognito || keyboardOptions.isPasswordInput(),
    ) {
        BasicTextField(
            value = value,
            modifier = modifier
                .then(
                    if (testTag != null) {
                        Modifier.testTag(testTag)
                    } else {
                        Modifier
                    },
                )
                .defaultMinSize(
                    minWidth = TextFieldDefaults.MinWidth,
                )
                .bringIntoView(),
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(cursorBrushColor),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            decorationBox = @Composable { innerTextField ->
                PlainTextFieldDecorationBox(
                    modifier = boxModifier,
                    value = value.text,
                    innerTextField = innerTextField,
                    placeholder = placeholder,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                )
            },
        )
    }
}

@Composable
private fun IncognitoInputIf(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        IncognitoInput(content)
    } else {
        content()
    }
}

private fun KeyboardOptions.isPasswordInput(): Boolean =
    keyboardType == KeyboardType.Password ||
        keyboardType == KeyboardType.NumberPassword

@Composable
private fun PlainTextFieldDecorationBox(
    modifier: Modifier,
    value: String,
    innerTextField: @Composable () -> Unit,
    placeholder: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val transformedText = remember(value, visualTransformation) {
        visualTransformation.filter(AnnotatedString(value))
    }.text.text

    val isFocused = interactionSource.collectIsFocusedAsState().value
    val inputState = when {
        isFocused -> InputPhase.Focused
        transformedText.isEmpty() -> InputPhase.UnfocusedEmpty
        else -> InputPhase.UnfocusedNotEmpty
    }
    // Transitions from/to InputPhase.Focused are the most critical in the transition below.
    // UnfocusedEmpty <-> UnfocusedNotEmpty are needed when a single state is used to control
    // multiple text fields.
    val transition = updateTransition(inputState, label = "TextFieldInputState")

    val placeholderOpacity by transition.animateFloat(
        label = "PlaceholderOpacity",
        transitionSpec = {
            if (InputPhase.Focused isTransitioningTo InputPhase.UnfocusedEmpty) {
                tween(
                    durationMillis = PlaceholderAnimationDelayOrDuration,
                    easing = LinearEasing,
                )
            } else if (InputPhase.UnfocusedEmpty isTransitioningTo InputPhase.Focused ||
                InputPhase.UnfocusedNotEmpty isTransitioningTo InputPhase.UnfocusedEmpty
            ) {
                tween(
                    durationMillis = PlaceholderAnimationDuration,
                    delayMillis = PlaceholderAnimationDelayOrDuration,
                    easing = LinearEasing,
                )
            } else {
                spring()
            }
        },
    ) {
        val showLabel = false // we do not support displaying a label
        when (it) {
            InputPhase.Focused -> 1f
            InputPhase.UnfocusedEmpty -> if (showLabel) 0f else 1f
            InputPhase.UnfocusedNotEmpty -> 0f
        }
    }

    val decoratedPlaceholder: @Composable ((Modifier) -> Unit)? =
        if (placeholder != null && transformedText.isEmpty()) {
            @Composable { modifier ->
                Box(
                    modifier = modifier
                        .alpha(placeholderOpacity),
                ) {
                    Decoration(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            .combineAlpha(HighEmphasisAlpha),
                        typography = MaterialTheme.typography.bodyLarge,
                        content = placeholder,
                    )
                }
            }
        } else {
            null
        }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        if (decoratedPlaceholder != null) {
            decoratedPlaceholder(
                Modifier,
            )
        }
        Box(
            propagateMinConstraints = true,
        ) {
            innerTextField()
        }
    }
}

/**
 * Set content color, typography and emphasis for [content] composable
 */
@Composable
private fun Decoration(
    contentColor: Color,
    typography: TextStyle? = null,
    content: @Composable () -> Unit,
) {
    val contentWithColor: @Composable () -> Unit = @Composable {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            content = content,
        )
    }
    if (typography != null) ProvideTextStyle(typography, contentWithColor) else contentWithColor()
}

/**
 * An internal state used to animate a label and an indicator.
 */
private enum class InputPhase {
    // Text field is focused
    Focused,

    // Text field is not focused and input text is empty
    UnfocusedEmpty,

    // Text field is not focused but input text is not empty
    UnfocusedNotEmpty,
}

private const val PlaceholderAnimationDuration = 83
private const val PlaceholderAnimationDelayOrDuration = 67

val BiFlatValueHeightMin = 32.dp

@Composable
fun BiFlatTextField(
    modifier: Modifier = Modifier,
    label: TextFieldModel,
    value: TextFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    valueVisualTransformation: VisualTransformation = VisualTransformation.None,
    valueIncognito: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val labelInteractionSource = remember { MutableInteractionSource() }
    val valueInteractionSource = remember { MutableInteractionSource() }

    val labelBuffer = rememberFieldBuffer(
        text = label.text,
        textRevision = label.textRevision,
    )
    val valueBuffer = rememberFieldBuffer(
        text = value.text,
        textRevision = value.textRevision,
    )

    val isError = kotlin.run {
        val error = value.error != null || label.error != null
        rememberUpdatedState(error)
    }
    val hasFocusState = remember {
        mutableStateOf(false)
    }

    val isEmpty = remember(
        valueBuffer,
        labelBuffer,
    ) {
        derivedStateOf {
            valueBuffer.value.text.isBlank() || labelBuffer.value.text.isBlank()
        }
    }

    BiFlatContainer(
        modifier = modifier
            .onFocusChanged { state ->
                hasFocusState.value = state.hasFocus
            },
        isError = isError,
        isFocused = hasFocusState,
        isEmpty = isEmpty,
        shapeState = shapeState,
        expressive = expressive,
        label = {
            BiFlatTextFieldLabel(
                label = label,
                buffer = labelBuffer,
                interactionSource = labelInteractionSource,
            )
        },
        content = {
            BiFlatTextFieldValue(
                value = value,
                buffer = valueBuffer,
                interactionSource = valueInteractionSource,
                visualTransformation = valueVisualTransformation,
                incognito = valueIncognito,
            )
        },
        trailing = trailing,
    )
}

@Composable
fun ColumnScope.BiFlatTextFieldLabel(
    label: TextFieldModel,
    buffer: MutableState<TextFieldValue> = rememberFieldBuffer(
        text = label.text,
        textRevision = label.textRevision,
    ),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val updatedOnChange by rememberUpdatedState(label.onChange)
    ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
        PlainTextField(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .alpha(HighEmphasisAlpha),
            placeholder = label.hint?.let { hintText ->
                // composable
                {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            },
            value = buffer.value,
            enabled = label.onChange != null,
            isError = label.error != null,
            interactionSource = interactionSource,
            onValueChange = { next ->
                buffer.value = next
                updatedOnChange?.invoke(next.text)
            },
        )
    }
    ExpandedIfNotEmpty(
        valueOrNull = label.error,
    ) { error ->
        FlatTextFieldBadgeLegacy(
            error = error,
        )
    }
}

@Composable
fun ColumnScope.BiFlatTextFieldValue(
    value: TextFieldModel,
    buffer: MutableState<TextFieldValue> = rememberFieldBuffer(
        text = value.text,
        textRevision = value.textRevision,
    ),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    visualTransformation: VisualTransformation = VisualTransformation.None,
    incognito: Boolean = false,
) {
    val updatedOnChange by rememberUpdatedState(value.onChange)
    PlainTextField(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BiFlatValueHeightMin),
        placeholder = value.hint?.let { hintText ->
            // composable
            {
                Text(
                    text = hintText,
                    maxLines = 1,
                )
            }
        },
        visualTransformation = visualTransformation,
        value = buffer.value,
        enabled = value.onChange != null,
        isError = value.error != null,
        interactionSource = interactionSource,
        incognito = incognito,
        onValueChange = { next ->
            buffer.value = next
            updatedOnChange?.invoke(next.text)
        },
    )
    ExpandedIfNotEmpty(
        valueOrNull = value.error,
    ) { error ->
        FlatTextFieldBadgeLegacy(
            error = error,
        )
    }
}

@Composable
fun BiFlatContainer(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    isError: State<Boolean>,
    isFocused: State<Boolean>,
    isEmpty: State<Boolean>,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    label: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    FlatTextFieldSurface(
        modifier = modifier,
        isError = isError.value,
        isFocused = isFocused.value,
        shapeState = shapeState,
        expressive = expressive,
    ) {
        Row(
            modifier = contentModifier
                .padding(
                    start = 16.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                label()

                // Divider that pops up if we need to highlight that there are
                // two fields a user can edit.
                Box {
                    val showDivider by remember(
                        isError,
                        isFocused,
                        isEmpty,
                    ) {
                        derivedStateOf {
                            isError.value || isFocused.value || isEmpty.value
                        }
                    }

                    val padding by kotlin.run {
                        val target = if (showDivider) 8.dp else 0.dp
                        animateDpAsState(targetValue = target)
                    }
                    val alpha by kotlin.run {
                        val target = if (showDivider) 1f else 0f
                        animateFloatAsState(targetValue = target)
                    }
                    HorizontalDivider(
                        modifier = Modifier
                            .padding(vertical = padding)
                            .alpha(alpha = alpha),
                    )
                }

                content()
            }
            if (trailing != null) {
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                trailing()
            }
        }
    }
}

@Composable
private fun FlatTextFieldSurface(
    modifier: Modifier = Modifier,
    isError: Boolean,
    isFocused: Boolean,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
    content: @Composable () -> Unit,
) {
    val borderColorTargetBaseColor = when {
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val borderColorTarget = when {
        isError || isFocused -> borderColorTargetBaseColor
        else ->
            LocalContentColor.current
                .combineAlpha(0.1f)
                .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    }
    val borderColor by animateColorAsState(
        targetValue = borderColorTarget,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val shape = surfaceShape(
        shapeState = shapeState,
        expressive = expressive,
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        content()
    }
}

@Composable
fun FlatTextFieldBadgeLegacy(
    modifier: Modifier = Modifier,
    badge: TextFieldModel.Vl? = null,
    error: String? = null,
) {
    val type = when {
        error != null -> TextFieldModel.Vl.Type.ERROR
        badge != null -> badge.type
        else -> TextFieldModel.Vl.Type.ERROR
    }
    FlatTextFieldBadge(
        modifier = modifier,
        type = type,
        text = error ?: badge?.text ?: "",
    )
}

@Composable
fun FlatTextFieldBadge(
    modifier: Modifier = Modifier,
    type: TextFieldModel.Vl.Type,
    text: String,
) {
    val icon = null
    val color = when (type) {
        TextFieldModel.Vl.Type.SUCCESS -> MaterialTheme.colorScheme.okContainer
        TextFieldModel.Vl.Type.INFO -> MaterialTheme.colorScheme.infoContainer
        TextFieldModel.Vl.Type.WARNING -> MaterialTheme.colorScheme.warningContainer
        TextFieldModel.Vl.Type.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    FlatTextFieldBadge(
        modifier = modifier
            .padding(
                top = 8.dp,
                bottom = 8.dp,
            ),
        backgroundColor = color,
        text = text,
        icon = icon,
    )
}

@Composable
fun FlatTextFieldBadge(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    text: String,
    icon: ImageVector? = null,
) {
    val backgroundColorState = animateColorAsState(
        targetValue = backgroundColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val contentColor = run {
        val color = if (backgroundColor.luminance() > 0.5f) {
            Color.Black
        } else {
            Color.White
        }
        val tint = backgroundColor.copy(alpha = 0.1f)
        tint.compositeOver(color)
    }
    val contentColorState = animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .drawBehind {
                val color = backgroundColorState.value
                drawRect(color)
            }
            .padding(
                top = 4.dp,
                bottom = 4.dp,
                start = 8.dp,
                end = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpandedIfNotEmptyForRow(
            valueOrNull = icon,
        ) {
            Icon(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(14.dp),
                imageVector = it,
                contentDescription = null,
                tint = contentColorState.value,
            )
        }
        Text(
            modifier = Modifier
                .animateContentSize(),
            text = text,
            color = contentColorState.value,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun defaultLeading(): Boolean = !CurrentPlatform.hasWatch()

private fun defaultClearButton(): Boolean = !CurrentPlatform.hasWatch()
