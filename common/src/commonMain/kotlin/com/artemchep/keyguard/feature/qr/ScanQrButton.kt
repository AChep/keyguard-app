package com.artemchep.keyguard.feature.qr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon

@Composable
fun ScanQrButton(
    onValueChange: ((String) -> Unit)? = null,
) {
    // A lambda to call to load the QR code
    // from a file.
    val onSelectFile = run {
        val loadQrState = produceLoadQrStateWithEffects(
            onValueChange = onValueChange,
        )
        loadQrState?.onSelectFile
    }

    val updatedOnValueChange by rememberUpdatedState(onValueChange)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val onScan = remember {
        // lambda
        {
            val route = registerRouteResultReceiver(ScanQrRoute) { rawValue ->
                controller.queue(NavigationIntent.Pop)
                // feed the result back
                updatedOnValueChange?.invoke(rawValue)
            }
            val intent = NavigationIntent.NavigateToRoute(
                route = route,
            )
            controller.queue(intent)
        }
    }

    val actions = remember(onSelectFile) {
        buildContextItems {
            if (onSelectFile != null) {
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.FileUpload),
                    title = Res.string.scanqr_load_from_image.wrap(),
                    onClick = onSelectFile,
                )
            }
            if (CurrentPlatform is Platform.Mobile) {
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.Camera),
                    title = Res.string.scanqr_title.wrap(),
                    onClick = onScan,
                )
            }
        }
    }

    var isContentDropdownExpanded by remember { mutableStateOf(false) }
    IconButton(
        enabled = onValueChange != null,
        onClick = {
            isContentDropdownExpanded = !isContentDropdownExpanded
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCodeScanner,
            contentDescription = null,
        )

        // Inject the dropdown popup to the bottom of the
        // content.
        val onDismissRequest = {
            isContentDropdownExpanded = false
        }
        KeyguardDropdownMenu(
            expanded = isContentDropdownExpanded,
            onDismissRequest = onDismissRequest,
        ) {
            actions.forEach { action ->
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}
