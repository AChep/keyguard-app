package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.di.resolveOrCancel
import kotlinx.coroutines.flow.first

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyBeginGetUnlockFlow(
    private val passkeyBeginGetRequest: PasskeyBeginGetRequest,
) {

    suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: BeginGetCredentialRequest,
        userVerified: Boolean,
    ): BeginGetCredentialResponse {
        val getCiphers = session.session.resolveOrCancel { get<GetCiphers>() }
        val getProfiles = session.session.resolveOrCancel { get<GetProfiles>() }
        val getSuggestions = session.session.resolveOrCancel { get<GetSuggestions<Any?>>() }
        val getPrivilegedApps = session.session.resolveOrCancel { get<GetPrivilegedApps>() }
        val equivalentDomainsBuilderFactory = session.session.resolveOrCancel { get<EquivalentDomainsBuilderFactory>() }
        val ciphers = filterHiddenProfiles(
            getProfiles = getProfiles,
            getCiphers = getCiphers,
            filter = null,
        ).first()
        val privilegedApps = getPrivilegedApps()
            .first()
        return passkeyBeginGetRequest.processGetCredentialsRequest(
            cipherHistoryOpenedRepository = session.session.resolveOrCancel { get() },
            getSuggestions = getSuggestions,
            equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
            request = request,
            ciphers = ciphers,
            privilegedApps = privilegedApps,
            userVerified = userVerified,
        )
    }
}
