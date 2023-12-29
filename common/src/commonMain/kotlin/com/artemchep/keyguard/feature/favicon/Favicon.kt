package com.artemchep.keyguard.feature.favicon

import com.artemchep.keyguard.common.usecase.GetAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

object Favicon {
    var servers: List<FaviconAccountServer> = emptyList()

    fun getServerOrNull(serverId: String?) = serverId
        ?.let { id ->
            servers.firstOrNull { it.id == id }
        }

    fun launch(
        scope: CoroutineScope,
        getAccounts: GetAccounts,
    ) {
        getAccounts()
            .map { accounts ->
                accounts
                    .map {
                        FaviconAccountServer(it)
                    }
            }
            .onEach { servers ->
                Favicon.servers = servers
            }
            .launchIn(scope)
    }
}
