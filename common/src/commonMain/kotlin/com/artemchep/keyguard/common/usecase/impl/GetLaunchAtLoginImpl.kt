package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import com.artemchep.keyguard.common.usecase.GetLaunchAtLogin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class GetLaunchAtLoginImpl(
    private val service: LaunchAtLoginService,
) : GetLaunchAtLogin {
    // Show the last known state at once: the OS re-read can take long,
    // and the settings pane and its search wait for every row.
    override fun invoke(): Flow<LaunchAtLoginState> = channelFlow {
        launch { service.refresh() }
        service.state.collect(::send)
    }
}
