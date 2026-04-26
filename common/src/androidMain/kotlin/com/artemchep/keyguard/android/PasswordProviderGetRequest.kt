package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.artemchep.keyguard.common.model.DSecret
import org.kodein.di.DirectDI

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasswordProviderGetRequest() {
    constructor(
        directDI: DirectDI,
    ) : this()

    suspend fun processGetCredentialsRequest(
        request: ProviderGetCredentialRequest,
        credential: DSecret,
    ): GetCredentialResponse {
        val login = requireNotNull(credential.login)
        val id = requireNotNull(login.username)
        val password = requireNotNull(login.password)
        return GetCredentialResponse(
            PasswordCredential(
                id = id,
                password = password,
            ),
        )
    }
}
