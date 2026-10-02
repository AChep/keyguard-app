package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavigationLifetimeTest {
    @Test
    fun `confirmation results belong to the originating presentation lifetime`() {
        val first = CoroutineScope(Job())
        val second = CoroutineScope(Job())
        val presented = mutableListOf<NavigationIntent>()
        val results = mutableListOf<String>()
        val firstInterceptor = first.guardNavigation { presented += it; true }
        val secondInterceptor = second.guardNavigation { presented += it; true }
        firstInterceptor(confirmation { results += "first" })
        secondInterceptor(confirmation { results += "second" })
        assertIs<DialogRoute>((presented[0] as NavigationIntent.NavigateToRoute).route)
        first.cancel()
        presented[0].resultRouteOrNull<ConfirmationRoute, ConfirmationResult>()!!.second(ConfirmationResult.Deny)
        presented[1].resultRouteOrNull<ConfirmationRoute, ConfirmationResult>()!!.second(ConfirmationResult.Deny)
        assertEquals(listOf("second"), results)
        assertTrue(firstInterceptor(confirmation { results += "late" }))
        assertEquals(2, presented.size)
        second.cancel()
    }

    @Test
    fun `nested results are guarded while unrelated navigation keeps its handling`() {
        val scope = CoroutineScope(Job())
        var presented: NavigationIntent? = null
        var results = 0
        val interceptor = scope.guardNavigation { presented = it; false }
        val browser = NavigationIntent.NavigateToBrowser("https://example.com")
        assertEquals(false, interceptor(browser))
        assertEquals(browser, presented)
        interceptor(NavigationIntent.Composite(listOf(confirmation { results++ }, browser)))
        val composite = assertIs<NavigationIntent.Composite>(presented)
        assertEquals(browser, composite.list[1])
        val transmitter = composite.list[0].resultRouteOrNull<ConfirmationRoute, ConfirmationResult>()!!.second
        transmitter(ConfirmationResult.Deny)
        assertEquals(1, results)
        scope.cancel()
        transmitter(ConfirmationResult.Deny)
        assertEquals(1, results)
    }

    private fun confirmation(onResult: () -> Unit): NavigationIntent = NavigationIntent.NavigateToRoute(
        registerRouteResultReceiver(ConfirmationRoute(ConfirmationRoute.Args())) { onResult() },
    )
}
