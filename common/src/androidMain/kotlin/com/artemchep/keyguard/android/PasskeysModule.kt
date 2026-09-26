package com.artemchep.keyguard.android

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistry
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.worker.Wrker
import org.koin.core.qualifier.named
import org.koin.dsl.module

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeysModule {
    val module = module {
        single {
            PasskeyBeginGetUnlockFlow(
                passkeyBeginGetRequest = get(),
            )
        }
        single {
            PasswordProviderGetFlow(
                getCredentialRequestUtils = get(),
            )
        }
        single {
            PasskeyProviderGetFlow(
                getCredentialRequestUtils = get(),
            )
        }
        single {
            CredentialProviderGetRequestHandler(
                context = get(),
                getVaultSession = get(),
                passkeyBeginGetUnlockFlow = get(),
                credentialProviderPlatformConfig = get(),
            )
        }
        single {
            PasskeyCreateRequest(
                context = get<Application>(),
                json = get(),
                passkeyUtils = get(),
                passkeyCrypto = get(),
            )
        }
        single {
            PasskeyBeginGetRequest(
                context = get<Application>(),
                json = get(),
                getAutofillPasskeysEnabled = get(),
                getAutofillPasswordsEnabled = get(),
                passkeyTargetCheck = get(),
                privilegedAppsService = get(),
                credentialProviderPlatformConfig = get(),
                passkeyUtils = get(),
            )
        }
        single {
            PasswordProviderGetRequest()
        }
        single {
            PasskeyProviderGetRequest(
                context = get<Application>(),
                json = get(),
                base64Service = get(),
                cryptoService = get(),
                passkeyCrypto = get(),
                passkeyUtils = get(),
            )
        }
        single {
            PasskeyUtils(
                cryptoService = get(),
                privilegedAppsService = get(),
                tldService = get(),
                httpClient = get(qualifier = named("curl")),
            )
        }
        //
        // Credential exchange (CXF/CXP) export
        //
        single {
            CredentialExchangeRegistry(
                context = get<Application>(),
                logRepository = get(),
            )
        }
        // The application worker registry includes this worker on supported Android versions.
        single<CredentialExchangeRegistrationWorker> {
            CredentialExchangeRegistrationWorker(
                registry = get<CredentialExchangeRegistry>(),
                exposedAccountRepository = get(),
                logRepository = get(),
            )
        }
    }
}
