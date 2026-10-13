package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.sso.BitwardenSsoRoute
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LoginRequestsTest {
    @Test
    fun ordinaryLoginDoesNotConsumeReloginCredentials() {
        val requests = LoginRequests()
        val request = LoginRequest.Form(
            BitwardenLoginRoute.Args(accountId = "account", email = "first@example.test", emailEditable = false),
        )
        val id = requests.put(request)
        assertEquals(LoginRequest.Form(), requests.request(null))
        assertEquals(request, requests.request(id))
    }

    @Test
    fun closingOneRequestPreservesTheOtherAndClearsOnlyItsOwnCredentials() {
        val requests = LoginRequests()
        val first = LoginRequest.Form(BitwardenLoginRoute.Args(accountId = "first", email = "first@example.test"))
        val second = LoginRequest.Sso(
            BitwardenSsoRoute.Args(accountId = "second", env = ServerEnv(), email = "second@example.test"),
        )
        val firstId = requests.put(first)
        val secondId = requests.put(second)
        assertNotEquals(firstId, secondId)
        assertEquals(first, requests.request(firstId))
        assertEquals(second, requests.request(secondId))
        requests.remove(firstId)
        requests.remove(firstId)
        assertEquals(LoginRequest.Form(), requests.request(firstId))
        assertEquals(second, requests.request(secondId))
    }
}
