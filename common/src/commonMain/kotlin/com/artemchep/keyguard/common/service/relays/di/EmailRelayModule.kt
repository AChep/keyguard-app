package com.artemchep.keyguard.common.service.relays.di

import com.artemchep.keyguard.common.service.relays.api.anonaddy.AnonAddyEmailRelay
import com.artemchep.keyguard.common.service.relays.api.cloudflare.CloudflareEmailRelay
import com.artemchep.keyguard.common.service.relays.api.duckduckgo.DuckDuckGoEmailRelay
import com.artemchep.keyguard.common.service.relays.api.fastmail.FastmailEmailRelay
import com.artemchep.keyguard.common.service.relays.api.firefoxrelay.FirefoxRelayEmailRelay
import com.artemchep.keyguard.common.service.relays.api.forwardemail.ForwardEmailEmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.simplelogin.SimpleLoginEmailRelay
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun emailRelayDiModule() = DI.Module("emailRelay") {
    //
    // Email relays
    //
    bindSingleton {
        AnonAddyEmailRelay(this)
    }
    bindSingleton {
        CloudflareEmailRelay(this)
    }
    bindSingleton {
        DuckDuckGoEmailRelay(this)
    }
    bindSingleton {
        FastmailEmailRelay(this)
    }
    bindSingleton {
        FirefoxRelayEmailRelay(this)
    }
    bindSingleton {
        ForwardEmailEmailRelay(this)
    }
    bindSingleton {
        SimpleLoginEmailRelay(this)
    }

    //
    // Common
    //

    // An aggregate of every registered relay, bound as the `EmailRelay` interface
    // so it can be resolved by an EXACT-type `instance<List<EmailRelay>>()` lookup.
    // The Apple/Native DI helper `leAllInstances<EmailRelay>()` only matches bindings
    // typed exactly as `EmailRelay` (the relays above are bound as their concrete
    // types), so the bridge resolves this list instead. Android keeps using
    // `leAllInstances` (Kodein's covariant search finds the concrete bindings there).
    bindSingleton<List<EmailRelay>> {
        listOf(
            instance<AnonAddyEmailRelay>(),
            instance<CloudflareEmailRelay>(),
            instance<DuckDuckGoEmailRelay>(),
            instance<FastmailEmailRelay>(),
            instance<FirefoxRelayEmailRelay>(),
            instance<ForwardEmailEmailRelay>(),
            instance<SimpleLoginEmailRelay>(),
        )
    }
}
