package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CipherDetailSessionTest {
    @Test
    fun `presentations keep snapshots actions and favourite callbacks independent`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<VaultDetailSnapshot>()
        val secondFrames = mutableListOf<VaultDetailSnapshot>()
        val invoked = mutableListOf<String>()
        first.session.observe({ firstFrames += it }, {})
        second.session.observe({ secondFrames += it }, {})
        first.publish(snapshot("A"), mapOf("copy" to { invoked += "copy A" }), { invoked += "favourite A" })
        second.publish(snapshot("B"), mapOf("copy" to { invoked += "copy B" }), { invoked += "favourite B" })

        first.session.invokeAction("copy")
        second.session.toggleFavorite()
        assertEquals(listOf("copy A", "favourite B"), invoked)
        assertEquals("A", firstFrames.last().cipherId)
        assertEquals("B", secondFrames.last().cipherId)

        first.session.close()
        first.session.invokeAction("copy")
        first.session.toggleFavorite()
        second.session.invokeAction("copy")
        assertEquals(listOf("copy A", "favourite B", "copy B"), invoked)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears actions and codes while an unlocked update can repopulate the session`() {
        val source = Source()
        var detail = snapshot("A")
        var totp: VaultDetailTotpSnapshot? = null
        var invocations = 0
        source.session.observe({ detail = it }, { totp = it })
        source.publish(snapshot("A"), mapOf("copy" to { invocations++ }), { invocations++ })
        source.publishTotp(VaultDetailTotpSnapshot("A", emptyMap()))
        assertEquals("A", totp?.cipherId)

        // The session observer publishes these values when its vault scope locks.
        source.publish(VaultDetailSnapshot.empty, emptyMap(), null)
        source.publishTotp(null)
        source.session.invokeAction("copy")
        source.session.toggleFavorite()
        assertEquals(0, invocations)
        assertEquals(VaultDetailSnapshot.empty, detail)
        assertNull(totp)

        source.publish(snapshot("A"), mapOf("copy" to { invocations++ }), null)
        source.session.invokeAction("copy")
        assertEquals(1, invocations)
        source.session.close()
    }

    @Test
    fun `cancel closes once and rejects late snapshots codes and actions`() {
        val source = Source()
        var frames = 0
        var codes = 0
        var invocations = 0
        val observation = source.session.observe({ frames++ }, { codes++ })
        observation.cancel()
        source.session.close()
        observation.cancel()
        source.publish(snapshot("late"), mapOf("copy" to { invocations++ }), { invocations++ })
        source.publishTotp(VaultDetailTotpSnapshot("late", emptyMap()))
        source.session.invokeAction("copy")
        source.session.toggleFavorite()
        assertEquals(1, source.cancellations)
        assertEquals(0, frames)
        assertEquals(0, codes)
        assertEquals(0, invocations)
        assertFailsWith<IllegalStateException> { source.session.observe({}, {}) }
    }

    @Test
    fun `close from the initial callback cancels the subscription returned afterwards`() {
        var cancellations = 0
        val session = CipherDetailSession { publish, _ ->
            publish(snapshot("A"), emptyMap(), null)
            KeyguardCancellable { cancellations++ }
        }
        session.observe({ session.close() }, {})
        assertEquals(1, cancellations)
    }

    private fun snapshot(id: String) = VaultDetailSnapshot.empty.copy(cipherId = id, title = id)

    private class Source {
        lateinit var publish: (VaultDetailSnapshot, Map<String, () -> Unit>, (() -> Unit)?) -> Unit
        lateinit var publishTotp: (VaultDetailTotpSnapshot?) -> Unit
        var cancellations = 0
        val session = CipherDetailSession { publish, publishTotp ->
            this.publish = publish
            this.publishTotp = publishTotp
            KeyguardCancellable { cancellations++ }
        }
    }
}
