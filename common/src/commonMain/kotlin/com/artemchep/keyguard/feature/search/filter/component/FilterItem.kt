package com.artemchep.keyguard.feature.search.filter.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.IconBoxContainer
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.util.DividerColor

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilterChipItemComposable(
    modifier: Modifier = Modifier,
    checked: Boolean,
    leading: (@Composable () -> Unit)?,
    title: String,
    text: String?,
    textMaxLines: Int? = null,
    onClick: (() -> Unit)?,
    enabled: Boolean = onClick != null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        ToggleButton(
            modifier = modifier
                .widthIn(
                    min = 48.dp,
                )
                .heightIn(
                    min = 32.dp,
                ),
            checked = checked,
            enabled = enabled,
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = LocalSurfaceColor.current,
                disabledContainerColor = LocalSurfaceColor.current,
            ),
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 4.dp,
            ),
            border = run {
                val borderColor = LocalContentColor.current
                    .combineAlpha(DisabledEmphasisAlpha)
                    .combineAlpha(MediumEmphasisAlpha)
                BorderStroke(Dp.Hairline, borderColor)
            },
            onCheckedChange = {
                updatedOnClick?.invoke()
            },
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp),
                ) {
                    leading()
                }
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
            }
            FilterItemComposableContent(
                title = title,
                text = text,
                textMaxLines = textMaxLines,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilterListItemComposable(
    modifier: Modifier = Modifier,
    checked: Boolean,
    leading: (@Composable () -> Unit)?,
    title: String,
    text: String?,
    textMaxLines: Int? = null,
    depth: Int = 0,
    expanded: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)?,
    enabled: Boolean = onClick != null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        val minHeight = 36.dp
        ToggleButton(
            modifier = modifier
                .widthIn(
                    min = 48.dp,
                )
                .heightIn(
                    min = minHeight,
                ),
            checked = checked,
            enabled = enabled,
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = LocalSurfaceColor.current,
                disabledContainerColor = LocalSurfaceColor.current,
            ),
            contentPadding = PaddingValues(),
            onCheckedChange = {
                updatedOnClick?.invoke()
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(
                        modifier = Modifier
                            .padding(
                                start = 9.dp,
                                end = 9.dp,
                            ),
                    ) {
                        val size = 16.dp

                        val times = depth.coerceAtMost(5)
                        repeat(times) {
                            if (it == times - 1) {
                                Box(
                                    modifier = Modifier
                                        .size(size)
                                        .padding(
                                            start = 2.dp,
                                            top = 6.dp,
                                            bottom = 6.dp,
                                            end = 10.dp,
                                        )
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape,
                                        ),
                                )
                                return@repeat
                            }
                            Spacer(
                                modifier = Modifier
                                    .width(size),
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .width(size),
                        )
                    }

                    val updatedExpanded by rememberUpdatedState(expanded)
                    val updatedOnToggle by rememberUpdatedState(onToggle)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                if (onToggle != null) {
                                    Modifier
                                        .clickable {
                                            val newExpanded = updatedExpanded != true
                                            updatedOnToggle?.invoke(newExpanded)
                                        }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(
                                start = 8.dp,
                                end = 8.dp,
                            )
                            .width(18.dp)
                            .height(minHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded == true) {
                                90f
                            } else {
                                0f
                            },
                        )
                        if (leading != null) {
                            IconBoxContainer(
                                main = {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp),
                                    ) {
                                        leading()
                                    }
                                },
                                secondary = if (expanded != null) {
                                    // composable
                                    {
                                        Icon(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer(
                                                    rotationZ = rotation,
                                                ),
                                            imageVector = Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                        )
                                    }
                                } else {
                                    null
                                },
                                compact = true,
                            )
                        } else if (expanded != null) {
                            IconBoxContainer(
                                main = {
                                    Icon(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .graphicsLayer(
                                                rotationZ = rotation,
                                            ),
                                        imageVector = Icons.Outlined.ChevronRight,
                                        contentDescription = null,
                                    )
                                },
                                compact = true,
                            )
                        }
                    }
                }

                FilterItemComposableContent(
                    title = title,
                    text = text,
                    textMaxLines = textMaxLines,
                )
            }
        }
    }
}

@Composable
private fun FilterItemComposableContent(
    title: String,
    text: String?,
    textMaxLines: Int?,
) {
    Column {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = LocalContentColor.current,
            style = MaterialTheme.typography.labelLarge,
        )
        val summaryOrNull = text?.takeUnless { it.isBlank() }
        ExpandedIfNotEmpty(valueOrNull = summaryOrNull) { summary ->
            Text(
                summary,
                maxLines = textMaxLines ?: 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(alpha = MediumEmphasisAlpha),
            )
        }
    }
}

@Composable
fun FilterItemLayout(
    modifier: Modifier = Modifier,
    checked: Boolean,
    leading: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    enabled: Boolean = onClick != null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)

    val backgroundColor =
        if (checked) {
            MaterialTheme.colorScheme.selectedContainer
        } else Color.Transparent
    Surface(
        modifier = modifier
            .semantics { role = Role.Checkbox },
        border = if (checked) null else BorderStroke(Dp.Hairline, DividerColor),
        color = backgroundColor,
        shape = CircleShape,
    ) {
        val contentColor = LocalContentColor.current
            .let { color ->
                if (enabled) {
                    color // enabled
                } else {
                    color.combineAlpha(DisabledEmphasisAlpha)
                }
            }
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (updatedOnClick != null) {
                            Modifier
                                .clickable(role = Role.Button) {
                                    updatedOnClick?.invoke()
                                }
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = 32.dp)
                    .padding(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Box(
                        modifier = Modifier
                            .size(18.dp),
                    ) {
                        leading()
                    }
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                }
                Column {
                    content()
                }
            }
        }
    }
}
