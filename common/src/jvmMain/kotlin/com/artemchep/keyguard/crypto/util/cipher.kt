package com.artemchep.keyguard.crypto.util

import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

fun createAesCbc(
    iv: ByteArray,
    key: ByteArray,
    forEncryption: Boolean,
) = run {
    val aes = PaddedBufferedBlockCipher(
        CBCBlockCipher.newInstance(
            AESEngine.newInstance(),
        ),
        PKCS7Padding(),
    )
    val ivAndKey: CipherParameters = ParametersWithIV(KeyParameter(key), iv)
    aes.init(forEncryption, ivAndKey)
    aes
}

fun BufferedBlockCipher.encode(data: ByteArray): ByteArray {
    val minSize = this.getOutputSize(data.size)
    val outBuf = ByteArray(minSize)
    val length1 = this.processBytes(data, 0, data.size, outBuf, 0)
    val length2 = this.doFinal(outBuf, length1)
    val actualLength = length1 + length2
    val result = ByteArray(actualLength)
    System.arraycopy(outBuf, 0, result, 0, result.size)
    return result
}
