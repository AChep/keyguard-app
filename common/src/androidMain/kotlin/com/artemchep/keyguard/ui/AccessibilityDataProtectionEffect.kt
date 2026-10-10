package com.artemchep.keyguard.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun AccessibilityDataProtectionEffect() {
    val view = LocalView.current
    SideEffect {
        // Keep protection for the lifetime of this app-owned window, including exit animations.
        view.protectAccessibilityData()
    }
}

/** Restricts this window's accessibility data to declared accessibility tools. */
fun View.protectAccessibilityData() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

    // Mark the Compose host explicitly: its inferred sensitivity may already be cached.
    setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    val root = rootView
    root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    val delegate = root.accessibilityDelegate
    if (delegate !is SensitiveAccessibilityDelegate) {
        root.accessibilityDelegate = SensitiveAccessibilityDelegate(
            delegate ?: View.AccessibilityDelegate(),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class SensitiveAccessibilityDelegate(
    private val delegate: View.AccessibilityDelegate,
) : View.AccessibilityDelegate() {
    override fun onRequestSendAccessibilityEvent(
        host: ViewGroup,
        child: View,
        event: AccessibilityEvent,
    ): Boolean {
        // Compose sets this from each virtual node's semantics, overriding the host's flag.
        // Protect events as they leave the window, including text and content descriptions.
        event.isAccessibilityDataSensitive = true
        val propagate = delegate.onRequestSendAccessibilityEvent(host, child, event)
        event.isAccessibilityDataSensitive = true
        return propagate
    }

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
        event.isAccessibilityDataSensitive = true
        delegate.sendAccessibilityEventUnchecked(host, event)
    }

    override fun sendAccessibilityEvent(host: View, eventType: Int) =
        delegate.sendAccessibilityEvent(host, eventType)

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        delegate.performAccessibilityAction(host, action, args)

    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(host, event)

    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
        delegate.onPopulateAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
        delegate.onInitializeAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) =
        delegate.onInitializeAccessibilityNodeInfo(host, info)

    override fun addExtraDataToAccessibilityNodeInfo(
        host: View,
        info: AccessibilityNodeInfo,
        extraDataKey: String,
        arguments: Bundle?,
    ) = delegate.addExtraDataToAccessibilityNodeInfo(host, info, extraDataKey, arguments)

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider? =
        delegate.getAccessibilityNodeProvider(host)
}
