package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.test.createSecret

/** A login whose URIs match each of the [urls] by domain. */
internal fun domainLoginSecret(
    vararg urls: String,
): DSecret = createSecret(
    id = "cipher",
    name = "Login",
    accountId = "account",
    uris = urls.map { url ->
        DSecret.Uri(
            uri = url,
            match = DSecret.Uri.MatchType.Domain,
        )
    },
    login = DSecret.Login(),
    createdDate = null,
)
