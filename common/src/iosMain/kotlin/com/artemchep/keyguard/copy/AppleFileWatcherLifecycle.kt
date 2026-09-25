package com.artemchep.keyguard.copy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState.UIApplicationStateBackground
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol

internal actual suspend fun watchAppleFileWatcherLifecycle(
    onForegroundChanged: (Boolean) -> Unit,
): Nothing = withContext(Dispatchers.Main) {
    val center = NSNotificationCenter.defaultCenter
    val observers = mutableListOf<NSObjectProtocol>()
    try {
        // A nil queue delivers synchronously, so presenters are removed before
        // the background notification returns and iOS can suspend the process.
        observers += center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) {
            onForegroundChanged(false)
        }
        observers += center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null,
        ) {
            onForegroundChanged(true)
        }
        // Inactive includes transient interruptions while the app is visible.
        onForegroundChanged(
            UIApplication.sharedApplication.applicationState != UIApplicationStateBackground,
        )
        awaitCancellation()
    } finally {
        observers.forEach { observer ->
            center.removeObserver(observer)
        }
    }
}
