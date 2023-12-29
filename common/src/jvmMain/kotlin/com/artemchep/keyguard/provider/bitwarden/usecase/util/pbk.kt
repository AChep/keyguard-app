package com.artemchep.keyguard.provider.bitwarden.usecase.util

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory

actual fun pbk(privateKey: ByteArray): ByteArray {
    val a = ASN1Sequence.fromByteArray(privateKey)
    val b = PrivateKeyInfo.getInstance(a)
    val d = PrivateKeyFactory.createKey(b) as RSAPrivateCrtKeyParameters
    val pub = RSAKeyParameters(false, d.modulus, d.publicExponent)
    val q1 = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)
    val pubKey = RSAPublicKey.getInstance(q1.parsePublicKey())
    val p = PublicKeyFactory.createKey(q1)
    val fm = ubyteArrayOf(
        48u,
        130u,
        1u,
        34u,
        48u,
        13u,
        6u,
        9u,
        42u,
        134u,
        72u,
        134u,
        247u,
        13u,
        1u,
        1u,
        1u,
        5u,
        0u,
        3u,
        130u,
        1u,
        15u,
        0u,
    )
    return (fm).toByteArray() + pubKey.encoded
}
