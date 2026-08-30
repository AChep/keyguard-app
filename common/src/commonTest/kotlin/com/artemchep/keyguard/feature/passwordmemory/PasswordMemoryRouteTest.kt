package com.artemchep.keyguard.feature.passwordmemory

import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PasswordMemoryRouteTest {
    @Test
    fun `descriptor is independent of the selected password`() {
        val first = PasswordMemoryRoute(PasswordMemoryRoute.Args(password = "first secret"))
        val second = PasswordMemoryRoute(PasswordMemoryRoute.Args(password = "second secret"))

        assertEquals(RouteDescriptor.PasswordMemory, first.descriptor)
        assertEquals(first.descriptor, second.descriptor)
    }

    @Test
    fun `descriptor serializes only its stable identity and round trips`() {
        val route = PasswordMemoryRoute(PasswordMemoryRoute.Args(password = "a secret"))

        val encoded = Json.encodeToString<RouteDescriptor>(route.descriptor)

        assertEquals("""{"type":"vault.cipher.password_memory"}""", encoded)
        assertEquals(RouteDescriptor.PasswordMemory, Json.decodeFromString<RouteDescriptor>(encoded))
    }

    @Test
    fun `empty password does not create a memory test action`() {
        val action = PasswordMemoryRoute.testPasswordMemoryActionOrNull(
            password = "",
            navigate = {},
        )

        assertNull(action)
    }

    @Test
    fun `action navigates to memory test for the selected password`() {
        var receivedIntent: NavigationIntent? = null
        val action = PasswordMemoryRoute.testPasswordMemoryActionOrNull(
            password = "a secret",
            navigate = { receivedIntent = it },
        )

        assertNotNull(action)
        assertNotNull(action.onClick).invoke()

        val intent = assertIs<NavigationIntent.NavigateToRoute>(receivedIntent)
        val route = assertIs<PasswordMemoryRoute>(intent.route)
        assertEquals("a secret", route.args.password)
    }
}
