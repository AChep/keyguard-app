package com.artemchep.keyguard.feature.home.vault.organizations

import com.artemchep.keyguard.feature.navigation.Route

interface OrganizationsRouteFactory {
    fun create(
        args: OrganizationsRoute.Args,
    ): Route
}

object OrganizationsRouteFactoryDefault : OrganizationsRouteFactory {
    override fun create(
        args: OrganizationsRoute.Args,
    ): Route {
        return OrganizationsRoute(
            args = args,
        )
    }
}
