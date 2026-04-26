package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.passkeys.WearPasskeysCredentialViewRoute

object PasskeysCredentialViewRouteFactoryWear : PasskeysCredentialViewRouteFactory {
    override fun create(
        args: PasskeysCredentialViewRoute.Args,
    ): Route {
        return WearPasskeysCredentialViewRoute(
            args = args,
        )
    }
}
