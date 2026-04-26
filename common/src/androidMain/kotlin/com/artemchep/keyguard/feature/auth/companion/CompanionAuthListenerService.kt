package com.artemchep.keyguard.feature.auth.companion

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class CompanionAuthListenerService : WearableListenerService(), DIAware {
    override val di by closestDI()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val companionAuthBridge by instance<CompanionAuthBridgeAndroid>()

    override fun onMessageReceived(
        messageEvent: MessageEvent,
    ) {
        scope.launch {
            companionAuthBridge.onMessageReceived(messageEvent)
        }
    }

    override fun onChannelOpened(
        channel: ChannelClient.Channel,
    ) {
        scope.launch {
            companionAuthBridge.onChannelOpened(channel)
        }
    }
}
