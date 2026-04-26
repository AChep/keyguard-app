package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyBeginGetUnlockFlow(
    private val passkeyBeginGetRequest: PasskeyBeginGetRequest,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        passkeyBeginGetRequest = directDI.instance(),
    )

    suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: BeginGetCredentialRequest,
        userVerified: Boolean,
    ): BeginGetCredentialResponse {
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val getProfiles = session.di.direct.instance<GetProfiles>()
        val getSuggestions = session.di.direct.instance<GetSuggestions<Any?>>()
        val equivalentDomainsBuilderFactory = session.di.direct
            .instance<EquivalentDomainsBuilderFactory>()
        val ciphers = filterHiddenProfiles(
            getProfiles = getProfiles,
            getCiphers = getCiphers,
            filter = null,
        ).first()
        return passkeyBeginGetRequest.processGetCredentialsRequest(
            cipherHistoryOpenedRepository = session.di.direct.instance(),
            getSuggestions = getSuggestions,
            equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
            request = request,
            ciphers = ciphers,
            userVerified = userVerified,
        )
    }
}
