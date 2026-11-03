package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.http.configureKeyguardIconHttpClient
import com.artemchep.keyguard.common.service.icon.FaviconScraper
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.common.service.icon.IconResolverImpl
import com.artemchep.keyguard.common.usecase.GetIconImage
import com.artemchep.keyguard.common.usecase.impl.GetIconImageImpl
import io.ktor.client.HttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class ApplicationIconsModule {
    val module = module {
        // Shares the transport of the curl client; closing
        // this client leaves the borrowed engine open.
        single<HttpClient>(named("icons")) {
            val engine = get<HttpClient>(named("curl")).engine
            HttpClient(engine) {
                configureKeyguardIconHttpClient()
            }
        }
        single<FaviconScraper> {
            FaviconScraper(
                httpClient = get(named("icons")),
                json = get(),
            )
        }
        single<IconResolver> {
            IconResolverImpl(
                getVaultSession = get(),
                accountIconServerSessionAccess = get(),
                getWebsiteIcons = get(),
                getAppIcons = get(),
                faviconScraper = get(),
                googlePlayParser = get(),
                appStoreParser = get(),
                httpClient = get(named("icons")),
            )
        }
        single<GetIconImageImpl>() bind GetIconImage::class
    }
}
