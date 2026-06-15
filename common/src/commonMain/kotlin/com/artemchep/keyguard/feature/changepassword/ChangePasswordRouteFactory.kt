package com.artemchep.keyguard.feature.changepassword

import com.artemchep.keyguard.feature.navigation.Route

interface ChangePasswordRouteFactory {
    fun create(): Route
}

object ChangePasswordRouteFactoryDefault : ChangePasswordRouteFactory {
    override fun create(): Route {
        return ChangePasswordRoute
    }
}
