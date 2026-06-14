package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.rememberFlatSurfaceExpressiveColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceShape
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.LocalExpressive

@Stable
interface SettingPaneComponents {
    @Composable
    fun KgAction(
        icon: ImageVector?,
        subIcon: ImageVector? = null,
        badge: String? = null,
        title: @Composable RowScope.() -> Unit,
        text: (@Composable RowScope.() -> Unit)? = null,
        contentColor: Color = Color.Unspecified,
        trailing: (@Composable RowScope.() -> Unit)? = null,
        footer: (@Composable ColumnScope.() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        enabled: Boolean = onClick != null,
    )

    @Composable
    fun KgPicker(
        icon: ImageVector? = null,
        subIcon: ImageVector? = null,
        title: @Composable RowScope.() -> Unit,
        titleText: String,
        text: (@Composable RowScope.() -> Unit)? = null,
        trailing: (@Composable RowScope.() -> Unit)? = null,
        footer: (@Composable ColumnScope.() -> Unit)? = null,
        dropdown: List<ContextItem>,
    )

    @Composable
    fun KgSwitch(
        icon: ImageVector? = null,
        subIcon: ImageVector? = null,
        title: @Composable RowScope.() -> Unit,
        text: (@Composable RowScope.() -> Unit)? = null,
        footer: (@Composable ColumnScope.() -> Unit)? = null,
        checked: Boolean,
        onCheckedChange: ((Boolean) -> Unit)?,
    )

    @Composable
    fun KgBlock(
        content: @Composable ColumnScope.() -> Unit,
    )
}

object SettingPaneComponentsDefault : SettingPaneComponents {
    @Composable
    override fun KgAction(
        icon: ImageVector?,
        subIcon: ImageVector?,
        badge: String?,
        title: @Composable (RowScope.() -> Unit),
        text: @Composable (RowScope.() -> Unit)?,
        contentColor: Color,
        trailing: @Composable (RowScope.() -> Unit)?,
        footer: @Composable (ColumnScope.() -> Unit)?,
        onClick: (() -> Unit)?,
        enabled: Boolean,
    ) {
        FlatItemSimpleExpressive(
            shapeState = LocalSettingItemShape.current,
            leading = if (icon != null) {
                // composable
                {
                    val content = remember(icon, subIcon) {
                        movableContentOf {
                            IconBox(
                                main = icon,
                                secondary = subIcon,
                            )
                        }
                    }

                    if (badge != null) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(
                                        text = badge,
                                    )
                                }
                            },
                        ) {
                            content()
                        }
                    } else {
                        content()
                    }
                }
            } else {
                null
            },
            trailing = trailing,
            title = {
                Row {
                    title()
                }
            },
            text = if (text != null) {
                {
                    Row {
                        text()
                    }
                }
            } else {
                null
            },
            contentColor = contentColor
                .takeIf { it.isSpecified }
                ?: LocalContentColor.current,
            footer = footer,
            onClick = onClick,
            enabled = enabled,
        )
    }

    @Composable
    override fun KgPicker(
        icon: ImageVector?,
        subIcon: ImageVector?,
        title: @Composable (RowScope.() -> Unit),
        titleText: String,
        text: @Composable (RowScope.() -> Unit)?,
        trailing: @Composable (RowScope.() -> Unit)?,
        footer: @Composable (ColumnScope.() -> Unit)?,
        dropdown: List<ContextItem>,
    ) {
        FlatDropdownSimpleExpressive(
            shapeState = LocalSettingItemShape.current,
            leading = if (icon != null) {
                icon<RowScope>(icon, subIcon)
            } else {
                null
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Row {
                            title()
                        }
                    },
                    text = if (text != null) {
                        // composable
                        {
                            Row {
                                text()
                            }
                        }
                    } else {
                        null
                    },
                )
            },
            trailing = trailing,
            footer = footer,
            dropdown = dropdown,
        )
    }

    @Composable
    override fun KgSwitch(
        icon: ImageVector?,
        subIcon: ImageVector?,
        title: @Composable (RowScope.() -> Unit),
        text: @Composable (RowScope.() -> Unit)?,
        footer: @Composable (ColumnScope.() -> Unit)?,
        checked: Boolean,
        onCheckedChange: ((Boolean) -> Unit)?,
    ) {
        FlatItemSimpleExpressive(
            shapeState = LocalSettingItemShape.current,
            leading = if (icon != null) {
                icon<RowScope>(icon, subIcon)
            } else {
                null
            },
            trailing = {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                ) {
                    Switch(
                        checked = checked,
                        enabled = onCheckedChange != null,
                        onCheckedChange = onCheckedChange,
                    )
                }
            },
            title = {
                Row {
                    title()
                }
            },
            text = if (text != null) {
                // composable
                {
                    Row {
                        text()
                    }
                }
            } else {
                null
            },
            footer = footer,
            onClick = onCheckedChange?.partially1(!checked),
        )
    }

    @Composable
    override fun KgBlock(
        content: @Composable (ColumnScope.() -> Unit),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

val LocalSettingPaneComponents = staticCompositionLocalOf<SettingPaneComponents> {
    SettingPaneComponentsDefault
}

@Composable
fun SettingPaneComponents.KgAction(
    icon: ImageVector?,
    subIcon: ImageVector? = null,
    title: String,
    text: String? = null,
    contentColor: Color = Color.Unspecified,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = onClick != null,
) {
    KgAction(
        icon = icon,
        subIcon = subIcon,
        title = {
            Text(title)
        },
        text = if (text != null) {
            {
                Text(text)
            }
        } else {
            null
        },
        contentColor = contentColor,
        trailing = trailing,
        footer = footer,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun SettingPaneComponents.KgSwitch(
    icon: ImageVector? = null,
    subIcon: ImageVector? = null,
    title: String,
    text: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    KgSwitch(
        icon = icon,
        subIcon = subIcon,
        title = {
            Text(title)
        },
        text = if (text != null) {
            // composable
            {
                Text(text)
            }
        } else {
            null
        },
        footer = footer,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun SettingPaneComponents.KgPicker(
    icon: ImageVector? = null,
    subIcon: ImageVector? = null,
    title: String,
    titleText: String = title,
    text: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    dropdown: List<ContextItem>,
) {
    KgPicker(
        icon = icon,
        subIcon = subIcon,
        title = {
            Text(title)
        },
        titleText = titleText,
        text = if (text != null) {
            // composable
            {
                Text(text)
            }
        } else {
            null
        },
        trailing = trailing,
        footer = footer,
        dropdown = dropdown,
    )
}
