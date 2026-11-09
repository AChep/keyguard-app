package com.artemchep.keyguard

import com.artemchep.keyguard.feature.p2pwebrtc.WebRtcTransportFactory
import com.artemchep.keyguard.platform.AppleSessionMode
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import org.koin.dsl.module

/** How the native Apple apps reach the other devices of a sync group. */
internal class AppleKeyguardSyncModule {
    val module = module {
        single<KeyguardTransportFactory> {
            // The AutoFill extension is short-lived and never starts WebRTC.
            if (getOrNull<AppleSessionMode>() == AppleSessionMode.AUTOFILL) {
                KeyguardTransportFactory.RelayOnly
            } else {
                KeyguardTransportFactory.preferring(WebRtcTransportFactory())
            }
        }
    }
}
