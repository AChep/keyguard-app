package com.artemchep.keyguard.desktop.util

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** The events after which a window may have appeared on screen or left it. */
private val WINDOW_PRESENCE_EVENTS = setOf(
    WindowEvent.WINDOW_OPENED,
    WindowEvent.WINDOW_CLOSED,
    ComponentEvent.COMPONENT_SHOWN,
    ComponentEvent.COMPONENT_HIDDEN,
)

/**
 * Whether any window of the process is on screen, a minimized one included. It follows
 * every window the process opens, so a new kind of window needs no registration.
 */
internal fun hasShowingWindow(): Flow<Boolean> = callbackFlow {
    val listener = AWTEventListener { event ->
        if (event.source is Window && event.id in WINDOW_PRESENCE_EVENTS) {
            trySend(isAnyWindowShowing())
        }
    }
    val toolkit = Toolkit.getDefaultToolkit()
    toolkit.addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK)
    // Read after subscribing, so a change in between is not missed.
    send(isAnyWindowShowing())
    awaitClose { toolkit.removeAWTEventListener(listener) }
}
    // Only the newest state matters.
    .conflate()
    .distinctUntilChanged()

private fun isAnyWindowShowing() = Window.getWindows().any { it.isShowing }
