package com.artemchep.keyguard.core.session.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.platform.LeCipher
import kotlinx.coroutines.Dispatchers

actual fun ByteArray.encode(cipher: IO<LeCipher>) = cipher
    .effectMap(Dispatchers.Default) { c ->
        synchronized(c) {
            c.doFinal(this)
        }
    }

actual fun IO<ByteArray>.encode(cipher: IO<LeCipher>) = this
    .flatMap { bytes ->
        bytes.encode(cipher)
    }
