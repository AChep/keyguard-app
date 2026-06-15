package com.artemchep.keyguard.feature.send.view

import com.artemchep.keyguard.feature.navigation.Route

interface SendViewRouteFactory {
    fun create(
        sendId: String,
        accountId: String,
    ): Route
}

object SendViewRouteFactoryDefault : SendViewRouteFactory {
    override fun create(
        sendId: String,
        accountId: String,
    ): Route {
        return SendViewRoute(
            sendId = sendId,
            accountId = accountId,
        )
    }
}
