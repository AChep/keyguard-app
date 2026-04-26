package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import com.artemchep.keyguard.feature.home.settings.SettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles

internal val LocalWearSettingsTransformation = compositionLocalOf<SurfaceTransformation?> { null }

class WearSettingsComponents : SettingPaneComponents {
    @Composable
    override fun KgAction(
        icon: ImageVector?,
        subIcon: ImageVector?,
        title: @Composable RowScope.() -> Unit,
        text: (@Composable RowScope.() -> Unit)?,
        trailing: (@Composable RowScope.() -> Unit)?,
        footer: (@Composable ColumnScope.() -> Unit)?,
        onClick: (() -> Unit)?,
        enabled: Boolean,
    ) {
        val updatedOnClick by rememberUpdatedState(onClick)
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth(),
            label = {
                ProxyMaterial3Styles {
                    title()
                }
            },
            secondaryLabel = if (text != null) {
                // composable
                {
                    ProxyMaterial3Styles {
                        text()
                    }
                }
            } else {
                null
            },
            icon = if (icon != null) {
                // composable
                {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(ButtonDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            onClick = {
                updatedOnClick?.invoke()
            },
            enabled = enabled,
            transformation = LocalWearSettingsTransformation.current,
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
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth(),
            label = {
                ProxyMaterial3Styles {
                    title()
                }
            },
            secondaryLabel = if (text != null) {
                // composable
                {
                    ProxyMaterial3Styles {
                        text()
                    }
                }
            } else {
                null
            },
            icon = if (icon != null) {
                // composable
                {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(ButtonDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            onClick = {
                val route = WearPickerRoute(actions = dropdown)
                val intent = NavigationIntent.NavigateToRoute(route = route)
                navigationController.queue(intent)
            },
            enabled = dropdown.isNotEmpty(),
            transformation = LocalWearSettingsTransformation.current,
        )
    }

    @Composable
    override fun KgSwitch(
        icon: ImageVector?,
        subIcon: ImageVector?,
        title: @Composable RowScope.() -> Unit,
        text: (@Composable RowScope.() -> Unit)?,
        footer: (@Composable ColumnScope.() -> Unit)?,
        checked: Boolean,
        onCheckedChange: ((Boolean) -> Unit)?,
    ) {
        val updatedOnCheckedChange by rememberUpdatedState(onCheckedChange)
        SwitchButton(
            modifier = Modifier
                .fillMaxWidth(),
            checked = checked,
            enabled = onCheckedChange != null,
            onCheckedChange = { newChecked ->
                updatedOnCheckedChange?.invoke(newChecked)
            },
            label = {
                ProxyMaterial3Styles {
                    title()
                }
            },
            secondaryLabel = if (text != null) {
                // composable
                {
                    ProxyMaterial3Styles {
                        text()
                    }
                }
            } else {
                null
            },
            icon = if (icon != null) {
                // composable
                {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(ButtonDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            transformation = LocalWearSettingsTransformation.current,
        )
    }

    @Composable
    override fun KgBlock(
        content: @Composable ColumnScope.() -> Unit,
    ) {
        ProxyMaterial3Styles {
            Column {
                content()
            }
        }
    }
}
