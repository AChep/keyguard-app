package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection

fun BitwardenCollection.transform(
    crypto: BitwardenCrCta,
) = copy(
    name = crypto.transformString(name),
)
