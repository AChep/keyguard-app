package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.AgentFilterActions
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.AgentFiltersSession
import com.artemchep.keyguard.apple.core.AgentFiltersSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFormSessionTest {
    @Test
    fun `feedback edits and submission stay with their draft and stop on close`() {
        val messages = mutableListOf("", "")
        val sent = mutableListOf<String>()
        val sessions = messages.indices.map { index ->
            FeedbackSession { publish ->
                publish(
                    FeedbackSnapshot(loaded = true),
                    FeedbackActions(
                        setMessage = { messages[index] = it },
                        submit = { sent += messages[index] },
                    ),
                )
                KeyguardCancellable {}
            }.also { it.observe {} }
        }
        sessions[0].setMessage("first draft")
        sessions[1].setMessage("second draft")
        sessions[0].submit()
        sessions[0].close()
        sessions[0].setMessage("stale draft")
        sessions[0].submit()
        sessions[1].submit()
        assertEquals(listOf("first draft", "second draft"), messages)
        assertEquals(listOf("first draft", "second draft"), sent)
        sessions[1].close()
    }

    @Test
    fun `password editors route each field and completion to their owner`() {
        val edits = mutableListOf<String>()
        val completions = mutableListOf<() -> Unit>()
        val closed = mutableListOf<Int>()
        val sessions = (0..1).map { index ->
            ChangePasswordSession { publish, complete ->
                completions += complete
                publish(
                    ChangePasswordSnapshot(loaded = true),
                    ChangePasswordActions(
                        setCurrentPassword = { edits += "$index:current:$it" },
                        setNewPassword = { edits += "$index:new:$it" },
                        setBiometric = { edits += "$index:biometric:$it" },
                        submit = { edits += "$index:submit" },
                    ),
                )
                KeyguardCancellable {}
            }.also { it.observe({}, { closed += index }) }
        }
        sessions[0].setCurrentPassword("old A")
        sessions[1].setNewPassword("new B")
        sessions[1].setBiometric(false)
        sessions[1].submit()
        completions[1]()
        sessions[1].submit()
        sessions[0].close()
        completions[0]()
        sessions[0].setNewPassword("stale")
        assertEquals(listOf("0:current:old A", "1:new:new B", "1:biometric:false", "1:submit"), edits)
        assertEquals(listOf(1), closed)
    }

    @Test
    fun `ssh lock clears filter actions without affecting the other editor`() {
        val sources = mutableListOf<(AgentFiltersSnapshot, AgentFilterActions) -> Unit>()
        val edits = mutableListOf<String>()
        val sessions = (0..1).map {
            AgentFiltersSession { publish, _ ->
                sources += publish
                KeyguardCancellable {}
            }.also { it.observe({}, {}) }
        }
        sources.forEachIndexed { index, publish ->
            publish(
                AgentFiltersSnapshot.empty.copy(loaded = true),
                AgentFilterActions(
                    filters = mapOf("same-id" to { edits += "$index:filter" }),
                    save = { edits += "$index:save" },
                    reset = { edits += "$index:reset" },
                ),
            )
        }
        sessions[0].invokeFilter("same-id")
        sources[0](AgentFiltersSnapshot.empty, AgentFilterActions())
        sessions[0].save()
        sessions[0].reset()
        sessions[1].invokeFilter("same-id")
        sessions[1].reset()
        sessions[1].save()
        assertEquals(listOf("0:filter", "1:filter", "1:reset", "1:save"), edits)
        sessions.forEach { it.close() }
    }

    @Test
    fun `gpg closing one editor does not complete or clear the other`() {
        val edits = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val completions = mutableListOf<() -> Unit>()
        val sessions = (0..1).map { index ->
            AgentFiltersSession { publish, complete ->
                completions += complete
                publish(
                    AgentFiltersSnapshot.empty.copy(loaded = true),
                    AgentFilterActions(save = { edits += index }),
                )
                KeyguardCancellable {}
            }.also { it.observe({}, { closed += index }) }
        }
        sessions[0].close()
        sessions[0].save()
        completions[0]()
        sessions[1].save()
        completions[1]()
        sessions[1].save()
        assertEquals(listOf(1), edits)
        assertEquals(listOf(1), closed)
    }
}
