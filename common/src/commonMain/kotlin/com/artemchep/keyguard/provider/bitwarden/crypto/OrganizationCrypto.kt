package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization

fun BitwardenOrganization.transform(
    crypto: BitwardenCrCta,
) = copy(
    keyBase64 = crypto.transformBase64(keyBase64),
)
