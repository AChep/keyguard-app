package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MasterPasswordSessionTest {
    @Test
    fun `forms keep passwords options and submit callbacks with their owner`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<MasterPasswordSnapshot>()
        val secondFrames = mutableListOf<MasterPasswordSnapshot>()
        val calls = mutableListOf<String>()
        first.session.observe { firstFrames += it }
        second.session.observe { secondFrames += it }
        first.publish(MasterPasswordSnapshot.empty.copy(password = "first"), actions("A", calls))
        second.publish(MasterPasswordSnapshot.empty.copy(password = "second"), actions("B", calls))
        first.session.setPassword("edited")
        second.session.setBiometric(true)
        first.session.setCrashlytics(false)
        second.session.submit()
        assertEquals(listOf("A:password:edited", "B:biometric:true", "A:crashlytics:false", "B:submit"), calls)
        assertEquals("first", firstFrames.last().password)
        assertEquals("second", secondFrames.last().password)

        first.session.close()
        first.publish(MasterPasswordSnapshot.empty, actions("late", calls))
        invokeAll(first.session)
        second.session.setPassword("still open")
        assertEquals(1, firstFrames.size)
        assertEquals("B:password:still open", calls.last())
        assertEquals(5, calls.size)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `vault transition clears callbacks and cancellation rejects late delivery`() {
        val source = Source()
        val calls = mutableListOf<String>()
        val frames = mutableListOf<MasterPasswordSnapshot>()
        val subscription = source.session.observe { frames += it }
        source.publish(MasterPasswordSnapshot.empty.copy(password = "draft"), actions("A", calls))
        source.publish(MasterPasswordSnapshot.empty, MasterPasswordActions())
        invokeAll(source.session)
        assertEquals(emptyList(), calls)
        subscription.cancel()
        subscription.cancel()
        source.publish(MasterPasswordSnapshot.empty.copy(password = "late"), actions("late", calls))
        invokeAll(source.session)
        assertEquals(emptyList(), calls)
        assertEquals(2, frames.size)
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    @Test
    fun `synchronous close cancels the eventual producer exactly once`() {
        var cancellations = 0
        val calls = mutableListOf<String>()
        val session = MasterPasswordSession { publish ->
            publish(MasterPasswordSnapshot.empty, actions("A", calls))
            KeyguardCancellable { cancellations++ }
        }
        session.observe { session.close() }
        invokeAll(session)
        assertEquals(1, cancellations)
        assertEquals(emptyList(), calls)
    }

    private fun invokeAll(session: MasterPasswordSession) {
        session.setPassword("late")
        session.setBiometric(true)
        session.setCrashlytics(true)
        session.submit()
    }

    private fun actions(owner: String, calls: MutableList<String>) = MasterPasswordActions(
        setPassword = { calls += "$owner:password:$it" },
        setBiometric = { calls += "$owner:biometric:$it" },
        setCrashlytics = { calls += "$owner:crashlytics:$it" },
        submit = { calls += "$owner:submit" },
    )

    private class Source {
        lateinit var publish: (MasterPasswordSnapshot, MasterPasswordActions) -> Unit
        var cancellations = 0
        val session = MasterPasswordSession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
