package com.artemchep.keyguard.feature.passkeys

import com.artemchep.keyguard.feature.navigation.Route

interface PasskeysCredentialViewRouteFactory {
    fun create(
        args: PasskeysCredentialViewRoute.Args,
    ): Route
}

object PasskeysCredentialViewRouteFactoryDefault : PasskeysCredentialViewRouteFactory {
    override fun create(
        args: PasskeysCredentialViewRoute.Args,
    ): Route {
        return PasskeysCredentialViewRoute(
            args = args,
        )
    }
}
