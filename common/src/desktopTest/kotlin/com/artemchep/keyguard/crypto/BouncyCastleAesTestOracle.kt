package com.artemchep.keyguard.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

internal fun bouncyCastleAesCbcPkcs7(
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
    encrypt: Boolean,
): ByteArray {
    val cipher = PaddedBufferedBlockCipher(
        CBCBlockCipher.newInstance(AESEngine.newInstance()),
        PKCS7Padding(),
    ).apply {
        init(encrypt, ParametersWithIV(KeyParameter(key), iv))
    }
    val output = ByteArray(cipher.getOutputSize(data.size))
    val updateLength = cipher.processBytes(data, 0, data.size, output, 0)
    val finalLength = cipher.doFinal(output, updateLength)
    return output.copyOf(updateLength + finalLength)
}
