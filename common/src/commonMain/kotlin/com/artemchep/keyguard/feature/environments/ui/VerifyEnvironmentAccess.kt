package com.artemchep.keyguard.feature.environments.ui

import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessResult
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessRoute
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun verifyEnvironmentAccess(
    navigation: NavigationController,
): Boolean = verifyEnvironmentAccess(
    navigate = navigation::queue,
)

suspend fun verifyEnvironmentAccess(
    navigate: (NavigationIntent) -> Unit,
): Boolean = verifyEnvironmentAccessIfHandled { intent ->
    navigate(intent)
    true
}

/** Denies the access if [navigate] returns `false`, as nothing shows the prompt. */
suspend fun verifyEnvironmentAccessIfHandled(
    navigate: (NavigationIntent) -> Boolean,
): Boolean = suspendCancellableCoroutine { continuation ->
    val route = registerRouteResultReceiver(ElevatedAccessRoute()) { result ->
        if (continuation.isActive) continuation.resume(result is ElevatedAccessResult.Allow)
    }
    val handled = navigate(NavigationIntent.NavigateToRoute(route))
    if (!handled && continuation.isActive) {
        continuation.resume(false)
    }
}
