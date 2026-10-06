package com.artemchep.keyguard.copy

internal expect suspend fun watchAppleFileWatcherLifecycle(
    onForegroundChanged: (Boolean) -> Unit,
): Nothing
