package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.sso.BitwardenSsoRoute
import kotlin.uuid.Uuid

/** What a Bitwarden login presentation starts with. */
internal sealed interface LoginRequest {
    /** The sign-in form. */
    data class Form(val args: BitwardenLoginRoute.Args = BitwardenLoginRoute.Args()) : LoginRequest

    /** The SSO step, for an SSO account that logs in again. */
    data class Sso(val args: BitwardenSsoRoute.Args) : LoginRequest
}

/** Re-login arguments belong to the navigation request, never to the next arbitrary login. */
internal class LoginRequests {
    private val requests = mutableMapOf<String, LoginRequest>()

    fun put(request: LoginRequest): String = Uuid.random().toString().also { requests[it] = request }

    fun request(id: String?): LoginRequest = requests[id] ?: LoginRequest.Form()

    fun remove(id: String?) { requests.remove(id) }
}
