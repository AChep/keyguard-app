package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyProviderGetFlow(
    private val getCredentialRequestUtils: PasskeyProviderGetRequest,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        getCredentialRequestUtils = directDI.instance(),
    )

    suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: ProviderGetCredentialRequest,
        args: PasskeyProviderGetActivityArgs,
        userVerified: Boolean,
    ): GetCredentialResponse {
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val ciphers = getCiphers()
            .first()
        val credential = findCredentialOrNull(
            ciphers = ciphers,
            args = args,
        )
        requireNotNull(credential)

        val getPrivilegedApps = session.di.direct.instance<GetPrivilegedApps>()
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
        val addCipherUsedPasskey = session.di.direct.instance<AddCipherUsedPasskeyHistory>()
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
    .firstNotNullOfOrNull { cipher ->
        if (
            args.accountId != cipher.accountId ||
            args.cipherId != cipher.id
        ) {
            return@firstNotNullOfOrNull null
        }

        cipher.login?.fido2Credentials
            ?.firstOrNull { credential ->
                args.credId == credential.credentialId
            }
    }
