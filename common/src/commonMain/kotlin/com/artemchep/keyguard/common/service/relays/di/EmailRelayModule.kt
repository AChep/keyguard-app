package com.artemchep.keyguard.common.service.relays.di

import com.artemchep.keyguard.common.service.relays.api.anonaddy.AnonAddyEmailRelay
import com.artemchep.keyguard.common.service.relays.api.cloudflare.CloudflareEmailRelay
import com.artemchep.keyguard.common.service.relays.api.duckduckgo.DuckDuckGoEmailRelay
import com.artemchep.keyguard.common.service.relays.api.firefoxrelay.FirefoxRelayEmailRelay
import com.artemchep.keyguard.common.service.relays.api.forwardemail.ForwardEmailEmailRelay
import com.artemchep.keyguard.common.service.relays.api.simplelogin.SimpleLoginEmailRelay
import org.kodein.di.DI
import org.kodein.di.bindSingleton

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
//    bindSingleton {
//        FastmailEmailRelay(this)
//    }
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

}
