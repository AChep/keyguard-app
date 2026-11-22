package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.browser.BrowserExtensionHost
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserExtensionServiceImpl
import com.artemchep.keyguard.common.service.browser.BrowserExtensionStreams
import com.artemchep.keyguard.common.service.browser.BrowserPairingStore
import com.artemchep.keyguard.common.service.browser.BrowserPasskeyPickers
import com.artemchep.keyguard.common.service.browser.BrowserPasskeys
import com.artemchep.keyguard.common.service.browser.UnsupportedBrowserExtension
import com.artemchep.keyguard.common.service.cli.agent.CliAgentUsageRecorder
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionAccess
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.util.webauthn.WebAuthnRpIdValidator
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose

private class BrowserExtensionApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * The browser extension integration. App-scoped: the extensions learn the
 * vault's state and ask to unlock it while it is locked. Only the desktop
 * app serves browser extensions; the others bind [UnsupportedBrowserExtension].
 */
class ApplicationBrowserExtensionModule {
    val module = module {
        single { BrowserExtensionApplicationScope() } onClose { it?.scope?.cancel() }
        single<BrowserExtensionSessionAccess> {
            BrowserExtensionSessionAccess { key ->
                key.session.resolve {
                    BrowserExtensionSessionDependencies(
                        getCiphers = get(),
                        getProfiles = get(),
                        getSuggestions = get(),
                        equivalentDomainsBuilderFactory = get(),
                        getUrlBlocks = get(),
                        blockedUrlCheck = get(),
                        cipherUrlCheck = get(),
                        getAutofillDefaultMatchDetection = get(),
                        getTotpCode = get(),
                        addCipherUsedAutofillHistory = get(),
                        getCipherLastUsed = get(),
                        addUriCipher = get(),
                        addUrlBlock = get(),
                        getCanWrite = get(),
                        getCollections = get(),
                        addCredentialCipher = get(),
                        addCipherUsedPasskeyHistory = get(),
                        vaultSettings = get(),
                    )
                }
            }
        }
        single {
            BrowserPairingStore(
                store = get<KeyValueStoreFactory>().get(Files.BROWSER_EXTENSION),
                json = get(),
                logRepository = get(),
            )
        }
        single {
            val tldService = get<TldService>()
            BrowserPasskeys(
                rpIdValidator = WebAuthnRpIdValidator(
                    domainLookup = { host -> tldService.getDomainName(host).bind() },
                    // Reads the related origins of a relying party.
                    httpClient = get(qualifier = named("curl")),
                ),
                authenticator = get(),
                vaultSessionLocker = get(),
                supportedAlgorithms = get<PasskeyCrypto>().supportedAlgorithms,
            )
        } bind BrowserPasskeyPickers::class
        single {
            val settings = get<SettingsReadRepository>()
            BrowserExtensionServiceImpl(
                getVaultSession = get(),
                sessionAccess = get(),
                getBrowserExtensionEnabled = get(),
                putBrowserExtensionEnabled = get(),
                getBrowserExtensionRemovedConnectors = get(),
                putBrowserExtensionRemovedConnectors = get(),
                cryptoGenerator = get(),
                store = get(),
                tldService = get(),
                passwordGenerator = get(),
                passkeys = get(),
                logRepository = get(),
                scope = get<BrowserExtensionApplicationScope>().scope,
                recorder = get<CliAgentUsageRecorder>(),
                approvalCacheConfig = settings.getBrowserExtensionApprovalCacheConfig(),
                approvalTimeout = { settings.getBrowserExtensionApprovalTimeout().first() },
            )
        } binds arrayOf(
            BrowserExtensionService::class,
            BrowserExtensionHost::class,
            BrowserExtensionStreams::class,
        )
    }
}
