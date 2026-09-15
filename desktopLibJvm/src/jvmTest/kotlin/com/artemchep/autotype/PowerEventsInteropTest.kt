package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PowerEventsInteropTest {
    @Test
    fun `events are mapped and delivered synchronously on the calling thread`() {
        val fake = NativePowerFake()
        val events = mutableListOf<DesktopPowerEvent>()
        val threads = mutableListOf<Thread>()
        val result = registerDesktopPowerEvents(fake.lib, {
            events += it
            threads += Thread.currentThread()
        }, { throw AssertionError(it) })
        val registration = assertIs<DesktopPowerRegistrationResult.Success>(result).registration
        try {
            (1..4).forEach { code ->
                fake.callback!!.invoke(code)
                assertEquals(code, events.size)
            }
            fake.callback!!.invoke(0)
            fake.callback!!.invoke(5)
            assertEquals(DesktopPowerEvent.entries.toList(), events.toList())
            assertTrue(threads.all { it === Thread.currentThread() })
        } finally {
            registration.close()
        }
    }

    @Test
    fun `callback and error handler failures do not escape native delivery`() {
        val fake = NativePowerFake()
        var reported: Throwable? = null
        val problem = IllegalStateException("lock failed")
        val result = registerDesktopPowerEvents(fake.lib, { throw problem }, {
            reported = it
            error("reporting failed")
        })
        val registration = assertIs<DesktopPowerRegistrationResult.Success>(result).registration
        try {
            fake.callback!!.invoke(1)
            assertEquals(problem, reported)
        } finally {
            registration.close()
        }
    }

    @Test
    fun `failed cleanup retains an inert callback until a successful retry`() {
        val fake = NativePowerFake()
        val retention = ConcurrentHashMap.newKeySet<DesktopLibJna.PowerEventCallback>()
        var calls = 0
        val result = registerDesktopPowerEvents(fake.lib, { calls++ }, {}, retention)
        val registration = assertIs<DesktopPowerRegistrationResult.Success>(result).registration
        val callback = fake.callback!!
        assertEquals(1, retention.size)
        callback.invoke(1)
        assertEquals(1, calls)

        fake.removeResult = false
        assertFalse(registration.unregister())
        assertEquals(1, retention.size)
        callback.invoke(1)
        assertEquals(1, calls)

        fake.removeResult = true
        assertTrue(registration.unregister())
        assertEquals(0, retention.size)
        assertFalse(registration.unregister())
        callback.invoke(1)
        assertEquals(1, calls)
        assertEquals(2, fake.removeCalls)
    }

    @Test
    fun `registration errors release the callback and return a typed failure`() {
        listOf(-1, -5, 0).forEach { status ->
            val fake = NativePowerFake().apply { registerResult = status }
            val retention = ConcurrentHashMap.newKeySet<DesktopLibJna.PowerEventCallback>()
            val result = registerDesktopPowerEvents(fake.lib, { error("unexpected callback") }, {}, retention)
            val failure = assertIs<DesktopPowerRegistrationResult.Failure>(result)
            val expected = if (status == -1) DesktopPowerRegistrationFailureReason.UnsupportedPlatform
            else DesktopPowerRegistrationFailureReason.InternalError
            assertEquals(expected, failure.reason)
            assertEquals(0, retention.size)
        }
    }

    @Test
    fun `uncertain registration keeps an inert callback alive`() {
        val fake = NativePowerFake().apply { failRegistration = true }
        val retention = ConcurrentHashMap.newKeySet<DesktopLibJna.PowerEventCallback>()
        var calls = 0
        val result = registerDesktopPowerEvents(fake.lib, { calls++ }, {}, retention)
        assertIs<DesktopPowerRegistrationResult.Failure>(result)
        assertEquals(1, retention.size)
        fake.callback!!.invoke(1)
        assertEquals(0, calls)
    }

    private class NativePowerFake {
        var callback: DesktopLibJna.PowerEventCallback? = null
        var registerResult = 12
        var failRegistration = false
        var removeResult = true
        var removeCalls = 0
        val lib = Proxy.newProxyInstance(
            DesktopLibJna::class.java.classLoader,
            arrayOf(DesktopLibJna::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "registerNativePowerEvents" -> {
                    callback = arguments[0] as DesktopLibJna.PowerEventCallback
                    if (failRegistration) error("registration outcome unknown")
                    registerResult
                }
                "unregisterNativePowerEvents" -> {
                    assertEquals(12, arguments[0])
                    removeCalls++
                    removeResult
                }
                else -> error("Unexpected native call: ${method.name}")
            }
        } as DesktopLibJna
    }
}
