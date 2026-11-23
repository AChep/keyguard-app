package com.artemchep.keyguard.desktop.util

import java.awt.EventQueue
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ShowingWindowsTest {
    // No window is listed anywhere: showing and hiding one is all the flow needs.
    @Test
    fun `a window counts only while it is on screen`() {
        requireDesktopTestDisplay()
        val frame = JFrame()
        try {
            runBlocking {
                withTimeout(10.seconds) {
                    val states = Channel<Boolean>(Channel.UNLIMITED)
                    val collector = launch(Dispatchers.Default) {
                        hasShowingWindow().collect(states::send)
                    }
                    assertFalse(states.receive())
                    EventQueue.invokeAndWait { frame.isVisible = true }
                    assertTrue(states.receive())
                    EventQueue.invokeAndWait { frame.isVisible = false }
                    assertFalse(states.receive())
                    collector.cancel()
                }
            }
        } finally {
            EventQueue.invokeAndWait { frame.dispose() }
        }
    }
}
