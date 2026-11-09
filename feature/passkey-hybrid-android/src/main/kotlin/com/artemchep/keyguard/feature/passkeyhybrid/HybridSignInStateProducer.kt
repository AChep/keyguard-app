package com.artemchep.keyguard.feature.passkeyhybrid

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSessionProducer
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSessionState
import com.artemchep.keyguard.feature.passkeys.HybridVaultAdapter
import com.artemchep.keyguard.util.fidohybrid.HybridTransport
import com.artemchep.keyguard.util.fidohybrid.OkHttpHybridTunnelConnector
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import org.koin.compose.currentKoinScope

@Composable
internal fun produceHybridSignInState(): HybridSessionState {
    val koin = currentKoinScope()
    val context = LocalContext.current.applicationContext
    val getSession = koin.get<GetVaultSession>()
    val getCiphers = koin.get<GetCiphers>()
    val getProfiles = koin.get<GetProfiles>()
    val authenticator = koin.get<WebAuthnAuthenticator>()
    val recordUsage = koin.get<AddCipherUsedPasskeyHistory>()
    val canWrite = koin.get<GetCanWrite>()
    val collections = koin.get<GetCollections>()
    val addCredential = koin.get<AddCredentialCipher>()
    return produceScreenState(
        key = "passkey_hybrid",
        initial = HybridSessionState(),
        args = arrayOf(getCiphers),
    ) {
        HybridSessionProducer(
            scope = screenScope,
            vault =
                HybridVaultAdapter(
                    getSession = getSession,
                    visibleCiphers = filterHiddenProfiles(getCiphers, getProfiles),
                    ciphers = getCiphers(),
                    canWrite = canWrite(),
                    collections = collections(),
                    addCredential = addCredential,
                    recordUsage = recordUsage,
                ),
            authenticator = authenticator,
            transport = { qr, guard, waiting, handler ->
                HybridTransport(authenticator.aaguid()).run(
                    qr = qr,
                    connector = OkHttpHybridTunnelConnector(),
                    advertiser = AndroidHybridAdvertiser(context),
                    onWaiting = waiting,
                    authenticator = handler,
                    guard = guard,
                )
            },
        ).state
    }
}
