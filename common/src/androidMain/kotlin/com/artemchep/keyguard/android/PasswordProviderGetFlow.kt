package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasswordProviderGetFlow(
    private val getCredentialRequestUtils: PasswordProviderGetRequest,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        getCredentialRequestUtils = directDI.instance(),
    )

    suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: ProviderGetCredentialRequest,
        args: PasswordProviderGetActivityArgs,
        userVerified: Boolean,
    ): GetCredentialResponse {
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val ciphers = getCiphers()
            .first()
        val credential = findPasswordCipherOrNull(
            ciphers = ciphers,
            args = args,
        )
        requireNotNull(credential)

        return getCredentialRequestUtils.processGetCredentialsRequest(
            request = request,
            credential = credential,
        )
    }

    suspend fun recordUsage(
        session: MasterSession.Key,
        args: PasswordProviderGetActivityArgs,
    ) {
        val addCipherUsedAutofillHistory = session.di.direct.instance<AddCipherUsedAutofillHistory>()
        addCipherUsedAutofillHistory(
            AddCipherOpenedHistoryRequest(
                accountId = args.accountId,
                cipherId = args.cipherId,
            ),
        ).attempt().bind()
    }
}

internal fun findPasswordCipherOrNull(
    ciphers: List<DSecret>,
    args: PasswordProviderGetActivityArgs,
): DSecret? = ciphers.firstOrNull { cipher ->
    if (
        args.accountId != cipher.accountId ||
        args.cipherId != cipher.id
    ) {
        return@firstOrNull false
    }

    val login = cipher.login ?: return@firstOrNull false
    login.username == args.id && login.password != null
}
