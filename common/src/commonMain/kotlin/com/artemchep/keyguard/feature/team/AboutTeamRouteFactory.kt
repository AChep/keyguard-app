package com.artemchep.keyguard.feature.team

import com.artemchep.keyguard.feature.navigation.Route

interface AboutTeamRouteFactory {
    fun create(): Route
}

object AboutTeamRouteFactoryDefault : AboutTeamRouteFactory {
    override fun create(): Route {
        return AboutTeamRoute
    }
}
