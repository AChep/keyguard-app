package com.artemchep.keyguard.core.session.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.platform.LeCipher

actual fun ByteArray.encode(cipher: IO<LeCipher>): IO<ByteArray> =
    ioRaise(UnsupportedOperationException("Platform cipher is not supported on iOS yet."))

actual fun IO<ByteArray>.encode(cipher: IO<LeCipher>): IO<ByteArray> =
    ioRaise(UnsupportedOperationException("Platform cipher is not supported on iOS yet."))
