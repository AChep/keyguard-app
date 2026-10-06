package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import kotlin.uuid.Uuid

/** Re-login arguments belong to the navigation request, never to the next arbitrary login. */
internal class LoginRequests {
    private val requests = mutableMapOf<String, BitwardenLoginRoute.Args>()

    fun put(args: BitwardenLoginRoute.Args): String = Uuid.random().toString().also { requests[it] = args }

    fun args(id: String?): BitwardenLoginRoute.Args = requests[id] ?: BitwardenLoginRoute.Args()

    fun remove(id: String?) { requests.remove(id) }
}
