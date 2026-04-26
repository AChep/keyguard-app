package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.send.view.SendViewRouteFactory
import com.artemchep.keyguard.wear.feature.send.view.WearSendViewRoute

object SendViewRouteFactoryWear : SendViewRouteFactory {
    override fun create(
        sendId: String,
        accountId: String,
    ) = WearSendViewRoute(
            sendId = sendId,
            accountId = accountId,
        )
}
