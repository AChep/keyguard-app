package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.passkey.availablePasskeys
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.di.resolveOrCancel
import kotlinx.coroutines.flow.first

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyProviderGetFlow(
    private val getCredentialRequestUtils: PasskeyProviderGetRequest,
) {

    suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: ProviderGetCredentialRequest,
        args: PasskeyProviderGetActivityArgs,
        userVerified: Boolean,
    ): GetCredentialResponse {
        val getCiphers = session.session.resolveOrCancel { get<GetCiphers>() }
        val getProfiles = session.session.resolveOrCancel { get<GetProfiles>() }
        // The same items the entry was listed from: the vault may have changed since.
        val ciphers = filterHiddenProfiles(
            getCiphers = getCiphers,
            getProfiles = getProfiles,
        ).first()
        val credential = findCredentialOrNull(
            ciphers = ciphers,
            args = args,
        )
        requireNotNull(credential)

        val getPrivilegedApps = session.session.resolveOrCancel { get<GetPrivilegedApps>() }
        val privilegedApps = getPrivilegedApps()
            .first()
        return getCredentialRequestUtils.processGetCredentialsRequest(
            request = request,
            credential = credential,
            userVerified = userVerified,
            privilegedApps = privilegedApps,
        )
    }

    suspend fun recordUsage(
        session: MasterSession.Key,
        args: PasskeyProviderGetActivityArgs,
    ) {
        val addCipherUsedPasskey = session.session.resolveOrCancel { get<AddCipherUsedPasskeyHistory>() }
        addCipherUsedPasskey(
            AddCipherUsedPasskeyHistoryRequest(
                accountId = args.accountId,
                cipherId = args.cipherId,
                credentialId = args.credId,
            ),
        ).attempt().bind()
    }
}

internal fun findCredentialOrNull(
    ciphers: List<DSecret>,
    args: PasskeyProviderGetActivityArgs,
): DSecret.Login.Fido2Credentials? = ciphers
    .availablePasskeys()
    .firstNotNullOfOrNull { (cipher, credential) ->
        credential.takeIf {
            args.accountId == cipher.accountId &&
                args.cipherId == cipher.id &&
                args.credId == credential.credentialId
        }
    }
