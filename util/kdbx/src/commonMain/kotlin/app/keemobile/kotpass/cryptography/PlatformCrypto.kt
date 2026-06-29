package app.keemobile.kotpass.cryptography

import com.artemchep.keyguard.util.foundation.crypto.randomBytes

class SecureRandom {
    fun nextBytes(bytes: ByteArray) {
        randomBytes(bytes.size).copyInto(bytes)
    }
}
