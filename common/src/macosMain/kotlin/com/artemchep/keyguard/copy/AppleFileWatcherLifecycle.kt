package com.artemchep.keyguard.copy

import kotlinx.coroutines.awaitCancellation

internal actual suspend fun watchAppleFileWatcherLifecycle(
    onForegroundChanged: (Boolean) -> Unit,
): Nothing {
    onForegroundChanged(true)
    awaitCancellation()
}
