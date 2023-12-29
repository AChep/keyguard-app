package com.artemchep.keyguard.core.session.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeCipher

expect fun ByteArray.encode(cipher: IO<LeCipher>): IO<ByteArray>

expect fun IO<ByteArray>.encode(cipher: IO<LeCipher>): IO<ByteArray>
