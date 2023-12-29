package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder

fun BitwardenFolder.transform(
    crypto: BitwardenCrCta,
) = copy(
    // common
    name = crypto.transformString(name),
)
