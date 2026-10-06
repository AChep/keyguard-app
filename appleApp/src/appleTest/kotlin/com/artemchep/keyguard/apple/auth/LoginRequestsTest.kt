package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LoginRequestsTest {
    @Test
    fun ordinaryLoginDoesNotConsumeReloginCredentials() {
        val requests = LoginRequests()
        val args = BitwardenLoginRoute.Args(accountId = "account", email = "first@example.test", emailEditable = false)
        val id = requests.put(args)
        assertEquals(BitwardenLoginRoute.Args(), requests.args(null))
        assertEquals(args, requests.args(id))
    }

    @Test
    fun closingOneRequestPreservesTheOtherAndClearsOnlyItsOwnCredentials() {
        val requests = LoginRequests()
        val first = BitwardenLoginRoute.Args(accountId = "first", email = "first@example.test")
        val second = BitwardenLoginRoute.Args(accountId = "second", email = "second@example.test")
        val firstId = requests.put(first)
        val secondId = requests.put(second)
        assertNotEquals(firstId, secondId)
        assertEquals(first, requests.args(firstId))
        assertEquals(second, requests.args(secondId))
        requests.remove(firstId)
        requests.remove(firstId)
        assertEquals(BitwardenLoginRoute.Args(), requests.args(firstId))
        assertEquals(second, requests.args(secondId))
    }
}
