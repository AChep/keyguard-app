package com.artemchep.keyguard.feature.qr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver

@Composable
fun ScanQrButton(
    onValueChange: ((String) -> Unit)? = null,
) {
    val updatedOnValueChange by rememberUpdatedState(onValueChange)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    IconButton(
        enabled = onValueChange != null,
        onClick = {
            val route = registerRouteResultReceiver(ScanQrRoute) { rawValue ->
                controller.queue(NavigationIntent.Pop)
                // feed the result back
                updatedOnValueChange?.invoke(rawValue)
            }
            val intent = NavigationIntent.NavigateToRoute(
                route = route,
            )
            controller.queue(intent)
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCodeScanner,
            contentDescription = null,
        )
    }
}
