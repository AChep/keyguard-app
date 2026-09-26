package com.artemchep.keyguard.common.service.relays.di

import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.common.service.relays.api.anonaddy.AnonAddyEmailRelay
import com.artemchep.keyguard.common.service.relays.api.cloudflare.CloudflareEmailRelay
import com.artemchep.keyguard.common.service.relays.api.duckduckgo.DuckDuckGoEmailRelay
import com.artemchep.keyguard.common.service.relays.api.fastmail.FastmailEmailRelay
import com.artemchep.keyguard.common.service.relays.api.firefoxrelay.FirefoxRelayEmailRelay
import com.artemchep.keyguard.common.service.relays.api.forwardemail.ForwardEmailEmailRelay
import com.artemchep.keyguard.common.service.relays.api.simplelogin.SimpleLoginEmailRelay
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class EmailRelayModule {
    val module = module {
        //
        // Email relays
        //
        single<AnonAddyEmailRelay>()
        single<CloudflareEmailRelay>()
        single<DuckDuckGoEmailRelay>()
        single<FastmailEmailRelay>()
        single<FirefoxRelayEmailRelay>()
        single<ForwardEmailEmailRelay>()
        single<SimpleLoginEmailRelay>()

        //
        // Common
        //

        single<EmailRelayRegistry> {
            EmailRelayRegistry(
                listOf(
                    get<AnonAddyEmailRelay>(),
                    get<CloudflareEmailRelay>(),
                    get<DuckDuckGoEmailRelay>(),
                    get<FastmailEmailRelay>(),
                    get<FirefoxRelayEmailRelay>(),
                    get<ForwardEmailEmailRelay>(),
                    get<SimpleLoginEmailRelay>(),
                ),
            )
        }
    }
}
