package com.artemchep.keyguard.feature.websiteleak

import com.artemchep.keyguard.common.exception.watchtower.ServicePwnedDisabledException
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A failed breach check must reach the screen as a failure. Collapsing it
 * into an empty list shows "No breaches found" for a check that never ran.
 */
class WebsiteLeakFailureTest {
    @Test
    fun `disabled breach checks yield a failure and not an empty result`() = runTest {
        val state = screenScope().websiteLeakState(
            getBreaches = constant(ioRaise<HibpBreachGroup>(ServicePwnedDisabledException())),
        )
        assertIs<ServicePwnedDisabledException>(state.content.swap().getOrNull())
    }

    @Test
    fun `network failure yields a failure and not an empty result`() = runTest {
        val state = screenScope().websiteLeakState(
            getBreaches = constant(ioRaise<HibpBreachGroup>(IOException("offline"))),
        )
        assertIs<IOException>(state.content.swap().getOrNull())
    }

    @Test
    fun `successful check keeps the matching breaches`() = runTest {
        val group = HibpBreachGroup(
            breaches = listOf(
                HibpBreachResponse(title = "Example", domain = "example.com"),
                HibpBreachResponse(title = "Other", domain = "other.com"),
            ),
        )
        val state = screenScope().websiteLeakState(
            getBreaches = constant(io(group)),
        )
        val content = assertNotNull(state.content.getOrNull())
        assertEquals(listOf("example.com"), content.breaches.map { it.domain })
    }

    @Test
    fun `successful empty check yields an empty result`() = runTest {
        val state = screenScope().websiteLeakState(
            getBreaches = constant(io(HibpBreachGroup(breaches = emptyList()))),
        )
        val content = assertNotNull(state.content.getOrNull())
        assertEquals(emptyList(), content.breaches)
    }
}

// Runs the real producer against a fake breach source.
private suspend fun RememberStateFlowScope.websiteLeakState(
    getBreaches: GetBreaches,
): WebsiteLeakState = websiteLeakStateProducer(
    args = WebsiteLeakRoute.Args(host = "login.example.com"),
    getBreaches = getBreaches,
    dateFormatter = proxy<DateFormatter> { _, _ -> "Test" },
).first()

// The producer only touches the screen scope when the close action is
// invoked, so no screen call is expected.
private fun screenScope(): RememberStateFlowScope = unused()

private inline fun <reified T> constant(value: Any): T = proxy { method, _ ->
    check(method == "invoke") { "Unexpected ${T::class.simpleName}.$method" }
    value
}

private inline fun <reified T> unused(): T = proxy { method, _ ->
    error("Unexpected ${T::class.simpleName}.$method")
}

private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name, args.orEmpty())
    } as T
