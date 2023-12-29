package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend

fun BitwardenSend.transform(
    crypto: BitwardenCrCta,
    codec2: BitwardenCrCta,
) = copy(
    // common
    // key is encoded with profile key
    keyBase64 = keyBase64?.let(codec2::transformBase64),
    name = crypto.transformString(name),
    notes = crypto.transformString(notes),
    // types
    text = text?.transform(crypto),
    file = file?.transform(crypto),
)

fun BitwardenSend.File.transform(
    crypto: BitwardenCrCta,
) = copy(
    fileName = crypto.transformString(fileName),
    keyBase64 = keyBase64?.let(crypto::transformBase64),
)

fun BitwardenSend.Text.transform(
    crypto: BitwardenCrCta,
) = copy(
    text = crypto.transformString(text),
)
