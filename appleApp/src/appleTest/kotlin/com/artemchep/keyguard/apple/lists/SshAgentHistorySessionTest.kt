package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SshAgentHistorySessionTest {
    @Test
    fun `all history and cipher history keep their snapshots and lifetime independent`() {
        val all = Source()
        val cipher = Source()
        val allFrames = mutableListOf<SshAgentHistorySnapshot>()
        val cipherFrames = mutableListOf<SshAgentHistorySnapshot>()
        val observation = all.session.observe { allFrames += it }
        cipher.session.observe { cipherFrames += it }
        all.publish(snapshot("all"))
        cipher.publish(snapshot("cipher"))
        observation.cancel()
        all.session.close()
        all.publish(snapshot("late"))
        cipher.publish(snapshot("updated cipher"))
        assertEquals(listOf("all"), allFrames.map { it.subtitle })
        assertEquals(listOf("cipher", "updated cipher"), cipherFrames.map { it.subtitle })
        assertEquals(1, all.cancellations)
        assertEquals(0, cipher.cancellations)
        cipher.session.close()
    }

    @Test
    fun `lock resets history and cancelled sessions reject subsequent frames`() {
        val source = Source()
        val frames = mutableListOf<SshAgentHistorySnapshot>()
        val observation = source.session.observe { frames += it }
        source.publish(snapshot("cipher"))
        source.publish(SshAgentHistorySnapshot.empty)
        assertEquals(SshAgentHistorySnapshot.empty, frames.last())
        source.publish(snapshot("unlocked"))
        observation.cancel()
        observation.cancel()
        source.publish(snapshot("late"))
        assertEquals(listOf("cipher", null, "unlocked"), frames.map { it.subtitle })
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    @Test
    fun `closing during the initial callback cancels the eventual handle`() {
        var cancellations = 0
        val session = SshAgentHistorySession { publish ->
            publish(snapshot("cipher"))
            KeyguardCancellable { cancellations++ }
        }
        session.observe { session.close() }
        assertEquals(1, cancellations)
    }

    private fun snapshot(subtitle: String) = SshAgentHistorySnapshot.empty.copy(loaded = true, subtitle = subtitle)

    private class Source {
        lateinit var publish: (SshAgentHistorySnapshot) -> Unit
        var cancellations = 0
        val session = SshAgentHistorySession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
