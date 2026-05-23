package com.artemchep.keyguard.feature.barcodetype

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.toHex

// In is better to keep it stable. The key is used to track
// the latest type of the barcode. Changing the format resets
// the saved state for everyone.
fun createBarcodeTypeHistoryKey(
    cryptoGenerator: CryptoGenerator,
    cipherLocalId: String,
    value: String,
): String = cryptoGenerator
    .hashMd5("$cipherLocalId:$value".encodeToByteArray())
    .toHex()
