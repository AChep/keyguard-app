package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.team.AboutTeamRouteFactory
import com.artemchep.keyguard.wear.feature.team.WearAboutTeamRoute

object AboutTeamRouteFactoryWear : AboutTeamRouteFactory {
    override fun create(): Route {
        return WearAboutTeamRoute
    }
}
