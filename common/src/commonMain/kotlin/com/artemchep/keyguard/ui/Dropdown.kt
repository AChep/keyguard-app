package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor

val DropdownMinWidth = 256.dp

@Composable
fun KeyguardDropdownMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable DropdownScope.() -> Unit,
) {
    DropdownMenu(
        modifier = modifier
            .widthIn(min = DropdownMinWidth),
        shape = MaterialTheme.shapes.large,
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        AccessibilityDataProtectionEffect()
        val scope = DropdownScopeImpl(
            parent = this,
            onDismissRequest = onDismissRequest,
        )
        CompositionLocalProvider(
            LocalSurfaceColor provides MaterialTheme.colorScheme.surfaceContainer,
        ) {
            content(scope)
        }
    }
}
