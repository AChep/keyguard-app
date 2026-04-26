package com.artemchep.keyguard.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_promo_title_no_direct_action
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearListCard(
    modifier: Modifier = Modifier,
    icon: (@Composable RowScope.() -> Unit)? = null,
    title: (@Composable RowScope.() -> Unit)? = null,
    text: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    transformation: SurfaceTransformation? = null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    val cardModifier = modifier
        .heightIn(min = 16.dp)
    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Row(
                            modifier = Modifier
                                .size(16.dp),
                        ) {
                            icon()
                        }
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                    }
                    if (title != null) {
                        val contentColor = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha)
                        val contentStyle = MaterialTheme.typography.labelSmall
                        CompositionLocalProvider(
                            LocalContentColor provides contentColor,
                            LocalTextStyle provides contentStyle,
                        ) {
                            title()
                        }
                    }
                }

                if (text != null) {
                    text()
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }

    if (onClick != null) {
        Card(
            modifier = cardModifier,
            onClick = {
                updatedOnClick?.invoke()
            },
            enabled = true,
            transformation = transformation,
            content = content,
        )
    } else {
        Card(
            modifier = cardModifier,
            transformation = transformation,
            content = content,
        )
    }
}


@Composable
internal fun WearListAction(
    modifier: Modifier = Modifier,
    title: String,
    text: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            Text(
                text = title,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = text
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                {
                    Text(
                        text = value,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        icon = leading
            ?.let { icon ->
                {
                    ProxyMaterial3Styles {
                        Row {
                            icon()
                        }
                    }
                }
            },
        onClick = onClick,
        transformation = transformation,
    )
}

@Composable
fun WearListAction(
    modifier: Modifier = Modifier,
    icon: (@Composable RowScope.() -> Unit)? = null,
    title: (@Composable RowScope.() -> Unit)? = null,
    text: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
    transformation: SurfaceTransformation? = null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    FilledTonalButton(
        modifier = modifier
            .fillMaxWidth(),
        label = {
            title?.invoke(this)
        },
        secondaryLabel = text,
        icon = if (icon != null) {
            // composable
            {
                Row {
                    icon()
                }
            }
        } else {
            null
        },
        onClick = {
            updatedOnClick?.invoke()
        },
        enabled = enabled,
        transformation = transformation,
    )
}

@Composable
fun WearListPicker(
    modifier: Modifier = Modifier,
    icon: (@Composable RowScope.() -> Unit)? = null,
    title: (@Composable RowScope.() -> Unit)? = null,
    text: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)?,
    dropdown: List<ContextItem>,
    transformation: SurfaceTransformation? = null,
) {
    FilledTonalButton(
        modifier = modifier
            .fillMaxWidth(),
        label = {
            title?.invoke(this)
        },
        secondaryLabel = text,
        icon = if (icon != null) {
            // composable
            {
                Row {
                    icon()
                }
            }
        } else {
            null
        },
        onClick = {
        },
        enabled = true,
        transformation = transformation,
    )
}

@Composable
fun WearListLabel(
    modifier: Modifier = Modifier,
    text: String,
    textAlign: TextAlign = TextAlign.Center,
    error: Boolean = false,
    transformation: SurfaceTransformation? = null,
) {
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
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = text,
            textAlign = textAlign,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
                    .combineAlpha(alpha = MediumEmphasisAlpha)
            },
        )
    }
}

@Composable
fun WearListLabel(
    modifier: Modifier = Modifier,
    text: AnnotatedString,
    textAlign: TextAlign = TextAlign.Center,
    error: Boolean = false,
    transformation: SurfaceTransformation? = null,
) {
    Text(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 16.dp,
                vertical = 4.dp,
            )
            .surfaceTransformation(transformation),
        text = text,
        textAlign = textAlign,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha)
        },
    )
}

@Composable
fun WearListEmpty(
    modifier: Modifier = Modifier,
    text: String,
    transformation: SurfaceTransformation? = null,
) {
    Row(
        modifier = modifier
            .surfaceTransformation(transformation),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}


@Composable
fun WearContextAction(
    modifier: Modifier = Modifier,
    item: ContextItem,
    transformation: SurfaceTransformation? = null,
) {
    when (item) {
        is ContextItem.Section -> {
            WearSectionHeader(
                title = item.title,
                modifier = modifier,
                emptyBehavior = WearSectionHeaderEmptyBehavior.NoOp,
                transformation = transformation,
            )
        }

        is ContextItem.Custom -> {
            // Do nothing
        }

        is FlatItemAction -> {
            val updatedOnClick by rememberUpdatedState(item)
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth(),
                icon = item.icon
                    ?.let { icon ->
                        // composable
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                            )
                        }
                    }
                    ?: item.leading?.let { content ->
                        // composable
                        {
                            ProxyMaterial3Styles {
                                content()
                            }
                        }
                    },
                label = {
                    Text(
                        text = textResource(item.title),
                    )
                },
                secondaryLabel = if (item.text != null) {
                    // composable
                    {
                        Text(
                            text = textResource(item.text)
                                .orEmpty(),
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    updatedOnClick.onClick?.invoke()
                },
                transformation = transformation,
            )
        }
    }
}
