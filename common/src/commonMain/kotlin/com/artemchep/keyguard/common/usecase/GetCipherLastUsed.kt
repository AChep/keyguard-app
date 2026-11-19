package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Instant

/**
 * The time each of the ciphers was last used on this device to sign in: by
 * autofill or with a passkey. A cipher that never was is absent.
 */
interface GetCipherLastUsed : (Set<String>) -> IO<Map<String, Instant>>
