package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator

/**
 * Creates or updates ciphers from the given requests, all within a single
 * transaction. A map key is the id of the cipher to update; a key that does
 * not match an existing cipher — `null` by convention — creates a new cipher
 * with a freshly minted id. Returns the affected cipher ids. To batch several
 * creates into one call, use [createCiphers].
 */
interface AddCipher : (
    Map<String?, CreateRequest>,
) -> IO<List<String>>

/**
 * Creates every request as a new cipher within a single transaction and
 * returns the created cipher ids. A map can hold only one `null` key, so each
 * request is keyed by a new UUID.
 */
fun AddCipher.createCiphers(
    requests: List<CreateRequest>,
    cryptoGenerator: CryptoGenerator,
): IO<List<String>> {
    if (requests.isEmpty()) {
        return io(emptyList())
    }

    val requestMap = requests
        .associateBy<CreateRequest, String?> {
            cryptoGenerator.uuid()
        }
    return this(requestMap)
}
