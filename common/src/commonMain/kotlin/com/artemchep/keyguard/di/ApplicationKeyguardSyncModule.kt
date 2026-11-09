package com.artemchep.keyguard.di

import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomConnector
import com.artemchep.keyguard.provider.keyguard.transport.GzipCompressor
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardIceServers
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayMailboxes
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

/**
 * The networking of device-to-device sync that outlives a vault session. Each application
 * root binds the `KeyguardTransportFactory`: whether it may use WebRTC is a property of
 * the app, not of `common`.
 */
internal class ApplicationKeyguardSyncModule {
    val module = module {
        single<Compressor> {
            GzipCompressor
        }

        single<KeyguardIceServers> {
            KeyguardIceServers(
                httpClient = get(qualifier = named("curl")),
            )
        }

        single<KeyguardRelayConnector> {
            KtorKeyguardRelayConnector(
                httpClient = get(qualifier = named("curl")),
            )
        }

        single<KeyguardRelayMailboxes> {
            KtorKeyguardRelayMailboxes(
                httpClient = get(qualifier = named("curl")),
            )
        }

        single<KeyguardRoomConnector>()
    }
}
