package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.changepassword.ChangePasswordRouteFactory
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.wear.feature.changepassword.WearChangePasswordRoute

object ChangePasswordRouteFactoryWear : ChangePasswordRouteFactory {
    override fun create(): Route {
        return WearChangePasswordRoute
    }
}
