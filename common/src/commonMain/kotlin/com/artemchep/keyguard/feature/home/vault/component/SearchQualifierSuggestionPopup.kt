package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun SearchQualifierSuggestionPopup(
    suggestion: String,
    onClick: () -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = {},
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        properties = PopupProperties(
            focusable = false,
        ),
    ) {
        SmartBadge(
            modifier = Modifier
                .padding(
                    horizontal = 8.dp,
                    vertical = 1.dp,
                ),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                )
            },
            title = suggestion,
            text = "Tab",
            onClick = onClick,
        )
    }
}
