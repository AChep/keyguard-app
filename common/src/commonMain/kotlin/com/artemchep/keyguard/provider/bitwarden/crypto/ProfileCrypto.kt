package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile

fun BitwardenProfile.transform(
    crypto: BitwardenCrCta,
) = copy(
    keyBase64 = crypto
        .withEnv(crypto.env.copy(key = BitwardenCrKey.AuthToken))
        .transformBase64(keyBase64),
    privateKeyBase64 = crypto.transformBase64(privateKeyBase64),
)
