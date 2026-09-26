package com.artemchep.keyguard.feature.auth.companion

import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompanionAuthListenerService : WearableListenerService(), KeyguardKoinOwner {
    override val koin get() = keyguardKoin()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val companionAuthBridge by lazy { koin.get<CompanionAuthBridgeAndroid>() }

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
