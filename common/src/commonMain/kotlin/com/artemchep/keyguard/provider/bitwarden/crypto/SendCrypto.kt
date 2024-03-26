package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend

fun BitwardenSend.transform(
    itemCrypto: BitwardenCrCta,
    globalCrypto: BitwardenCrCta,
) = copy(
    // common
    // key is encoded with profile key
    keyBase64 = keyBase64?.let(globalCrypto::transformBase64),
    name = itemCrypto.transformString(name),
    notes = itemCrypto.transformString(notes),
    // types
    text = text?.transform(itemCrypto),
    file = file?.transform(itemCrypto),
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
    text = crypto.transformString(requireNotNull(text)),
)
