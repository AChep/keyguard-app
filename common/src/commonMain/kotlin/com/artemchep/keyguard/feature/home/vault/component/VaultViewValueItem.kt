package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(ExperimentalLayoutApi::class)
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

    val updatedVerify by rememberUpdatedState(item.verify)
    val visibilityState = remember(
        item.private,
        item.hidden,
    ) { mutableStateOf(!item.private && !item.hidden) }
    FlatDropdown(
        modifier = modifier,
        elevation = item.elevation,
        content = {
            val shownValue = animatedConcealedText(
                text = value,
                concealed = !visibilityState.value,
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
                text = if (shownValue.isNotBlank()) {
                    // composable
                    {
                        Text(
                            modifier = Modifier
                                .animateContentSize(),
                            text = shownValue,
                            fontFamily = if (item.monospace) monoFontFamily else null,
                        )
                    }
                } else {
                    // composable
                    {
                        Text(
                            modifier = Modifier
                                .animateContentSize()
                                .alpha(MediumEmphasisAlpha),
                            text = stringResource(Res.strings.empty_value),
                            fontFamily = if (item.monospace) monoFontFamily else null,
                        )
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
            if (item.private && !item.hidden) {
                VisibilityToggle(
                    visible = visibilityState.value,
                    onVisibleChange = { shouldBeConcealed ->
                        val verify = updatedVerify
                        if (
                            verify != null &&
                            shouldBeConcealed
                        ) {
                            verify.invoke {
                                visibilityState.value = true
                            }
                            return@VisibilityToggle
                        }

                        visibilityState.value = shouldBeConcealed
                    },
                )
            }
            val sharedFLow = MutableSharedFlow<Unit>()
            sharedFLow.tryEmit(Unit)
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
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                }
            }

            item.trailing?.invoke(this)
        },
    )
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
