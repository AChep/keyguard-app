package com.artemchep.keyguard.ui

import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

internal fun topPresentedViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { scene ->
            scene.activationState == UISceneActivationStateForegroundActive
        }
        ?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { window ->
            window.isKeyWindow()
        }
        ?: return null

    var controller = window.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
