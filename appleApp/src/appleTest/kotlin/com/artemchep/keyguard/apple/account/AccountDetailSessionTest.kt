package com.artemchep.keyguard.apple.account

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountDetailSessionTest {
    @Test
    fun `presentations isolate snapshots actions and cancellation`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<String>()
        val secondFrames = mutableListOf<String>()
        val invoked = mutableListOf<String>()
        val observation = first.session.observe { firstFrames += it.title }
        second.session.observe { secondFrames += it.title }
        first.publish(snapshot("A"), mapOf("action" to { invoked += "A" }))
        second.publish(snapshot("B"), mapOf("action" to { invoked += "B" }))
        first.session.invokeAction("action")
        second.session.invokeAction("action")
        observation.cancel()
        first.session.close()
        first.publish(snapshot("late"), mapOf("action" to { invoked += "late" }))
        first.session.invokeAction("action")
        second.publish(snapshot("updated B"), mapOf("action" to { invoked += "updated B" }))
        second.session.invokeAction("action")
        assertEquals(listOf("A"), firstFrames)
        assertEquals(listOf("B", "updated B"), secondFrames)
        assertEquals(listOf("A", "B", "updated B"), invoked)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears account actions and unlock restores only current actions`() {
        val source = Source()
        var detail = AccountDetailSnapshot.empty
        var invoked = 0
        source.session.observe { detail = it }
        source.publish(snapshot("A"), mapOf("action" to { invoked++ }))
        source.publish(AccountDetailSnapshot.empty, emptyMap())
        source.session.invokeAction("action")
        assertEquals(0, invoked)
        assertEquals(AccountDetailSnapshot.empty, detail)
        source.publish(snapshot("A"), mapOf("action" to { invoked++ }))
        source.session.invokeAction("action")
        assertEquals(1, invoked)
        source.session.close()
    }

    private fun snapshot(title: String) = AccountDetailSnapshot.empty.copy(title = title)

    private class Source {
        lateinit var publish: (AccountDetailSnapshot, Map<String, () -> Unit>) -> Unit
        var cancellations = 0
        val session = AccountDetailSession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
