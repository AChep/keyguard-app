package com.artemchep.keyguard.platform

import javax.crypto.Cipher

actual typealias LeCipher = Cipher

actual val LeCipher.leIv: ByteArray get() = iv
