package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.shortcut.ShortcutTooltip
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewValueItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Value,
) {
    val contentColor = LocalContentColor.current
    val value = remember(item.value, item.colorize, contentColor) {
        if (item.colorize) {
            colorizePassword(
                password = item.value,
                contentColor = contentColor,
            )
        } else {
            AnnotatedString(item.value)
        }
    }

    val visibilityConfig = item.visibility
    val visibilityState = rememberVisibilityState(
        visibilityConfig,
    )

    val updatedVisibilityConfig by rememberUpdatedState(visibilityConfig)
    FlatDropdown(
        modifier = modifier,
        elevation = item.elevation,
        content = {
            val shownValue = animatedConcealedText(
                text = value,
                concealed = !visibilityState.value.value || visibilityConfig.hidden,
            )

            FlatItemTextContent2(
                title = if (!item.title.isNullOrBlank()) {
                    // composable
                    {
                        Text(item.title)
                    }
                } else {
                    null
                },
                text = {
                    Box(
                        modifier = Modifier
                            .animateContentHeight(),
                    ) {
                        val fontFamily = if (item.monospace) monoFontFamily else null
                        if (shownValue.isNotBlank()) {
                            Text(
                                text = shownValue,
                                fontFamily = fontFamily,
                                maxLines = item.maxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                modifier = Modifier
                                    .alpha(MediumEmphasisAlpha),
                                text = stringResource(Res.string.empty_value),
                                fontFamily = fontFamily,
                            )
                        }
                    }
                },
            )

            if (item.badge != null || item.badge2.isNotEmpty()) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.badge != null) {
                        com.artemchep.keyguard.ui.Ah(
                            score = item.badge.score,
                            text = item.badge.text,
                        )
                    }
                    item.badge2.forEach {
                        val s = it.collectAsState()
                        val v = s.value
                        if (v != null) {
                            com.artemchep.keyguard.ui.Ah(
                                score = v.score,
                                text = v.text,
                            )
                        }
                    }
                }
            }
        },
        dropdown = item.dropdown,
        leading = item.leading,
        trailing = {
            if (visibilityConfig.concealed && !visibilityConfig.hidden) {
                val visible = visibilityState.value.value
                VisibilityToggle(
                    visible = visible,
                    onVisibleChange = { possibleNewValue ->
                        updatedVisibilityConfig.transformUserEvent(possibleNewValue) { newValue ->
                            visibilityState.value = Visibility.Event(
                                value = newValue,
                                timestamp = Clock.System.now(),
                            )
                        }
                    },
                )
            }
            val onCopyAction = remember(item.dropdown) {
                item.dropdown
                    .firstNotNullOfOrNull {
                        val action = it as? FlatItemAction
                        action?.takeIf { it.type == FlatItemAction.Type.COPY }
                    }
            }
            if (onCopyAction != null) {
                val onCopy = onCopyAction.onClick
                IconButton(
                    enabled = onCopy != null,
                    onClick = {
                        onCopy?.invoke()
                    },
                ) {
                    ShortcutTooltip(
                        valueOrNull = onCopyAction.shortcut,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                        )
                    }
                }
            }

            item.trailing?.invoke(this)
        },
    )
}

@Composable
fun rememberVisibilityState(
    visibilityConfig: Visibility,
): MutableState<Visibility.Event> {
    val visibilityState = remember(
        visibilityConfig,
    ) {
        val initialValue = !visibilityConfig.concealed && !visibilityConfig.hidden
        val initialState = Visibility.Event(
            value = initialValue,
            timestamp = Instant.DISTANT_PAST,
        )
        mutableStateOf(initialState)
    }

    LaunchedEffect(visibilityConfig.globalConfig) {
        val cfg = visibilityConfig.globalConfig
            .takeIf { visibilityConfig.concealed }
            ?: return@LaunchedEffect
        // Update the value of a switch basing on the global
        // reveal state, if it is newer than the current one.
        cfg.globalRevealStateFlow
            .onEach { event ->
                val curValue = visibilityState.value
                if (curValue.timestamp < event.timestamp) {
                    visibilityState.value = event
                }
            }
            .collect()
    }
    return visibilityState
}

@Composable
fun ColumnScope.FlatItemTextContent2(
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (title != null) {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.labelMedium
                .let {
                    val color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha)
                    it.copy(color = color)
                },
        ) {
            title()
        }
    }
    if (text != null) {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodyLarge,
        ) {
            text()
        }
    }
}
