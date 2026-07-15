package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.common.util.toHex
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers
import org.bouncycastle.asn1.gnu.GNUObjectIdentifiers
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.ECPublicBCPGKey
import org.bouncycastle.bcpg.Ed25519PublicBCPGKey
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.RSAPublicBCPGKey
import org.bouncycastle.bcpg.X25519PublicBCPGKey
import org.bouncycastle.openpgp.PGPPublicKey
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Computes a GnuPG/libgcrypt keygrip for an OpenPGP public key. The keygrip is the SHA-1
 * hash libgcrypt derives from the *public* key parameters, and the gpg-agent addresses
 * every private-key operation by it, so Keyguard's value has to be byte-identical to the
 * one `gpg --with-keygrip` produces or gpg asks the agent for a key it never registered.
 *
 * This is NOT the canonical-S-expression hash of the whole key — it mirrors libgcrypt's
 * `_gcry_pk_hash_grip` exactly, which is algorithm specific:
 *  - RSA: SHA-1 of just the modulus n (encoded as an `%m` MPI); the exponent is ignored.
 *  - ECC: SHA-1 of the six curve/point components p,a,b,g,n,q, each wrapped in the tiny
 *    S-expression `(1:<c><len>:<bytes>)`. The five curve constants are libgcrypt's own
 *    fixed keygrip inputs (their signs are dropped — the magnitudes are hashed), and are
 *    hard-coded below; every value here is gpg-verified against `--with-keygrip`.
 */
internal object GpgKeygripCalculatorJvm {
    fun calculate(
        publicKey: PGPPublicKey,
    ): String {
        val input = when (publicKey.algorithm) {
            PublicKeyAlgorithmTags.RSA_GENERAL,
            PublicKeyAlgorithmTags.RSA_ENCRYPT,
            PublicKeyAlgorithmTags.RSA_SIGN,
                -> rsaKeygripInput(publicKey)

            // Legacy ECC keys (ECDH 18, ECDSA 19, EdDSA 22) all carry an ECPublicBCPGKey
            // whose curve OID selects the constants and whose encoded point yields q.
            PublicKeyAlgorithmTags.ECDH,
            PublicKeyAlgorithmTags.ECDSA,
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
                -> eccKeygripInputFromEcPublicKey(
                key = publicKey.publicKeyPacket.key as ECPublicBCPGKey,
            )

            // RFC 9580 native Ed25519 (27): the point is the bare 32-byte coordinate.
            PublicKeyAlgorithmTags.Ed25519,
                -> eccKeygripInput(
                curve = CURVE_ED25519,
                q = (publicKey.publicKeyPacket.key as Ed25519PublicBCPGKey).key,
            )

            // RFC 9580 native X25519 (25): the point is the bare 32-byte coordinate.
            PublicKeyAlgorithmTags.X25519,
                -> eccKeygripInput(
                curve = CURVE_CURVE25519,
                q = (publicKey.publicKeyPacket.key as X25519PublicBCPGKey).key,
            )

            else -> throw IllegalArgumentException(
                "Unsupported OpenPGP public-key algorithm for keygrip: ${publicKey.algorithm}",
            )
        }
        return MessageDigest.getInstance("SHA-1")
            .digest(input)
            .toHex()
            .uppercase()
    }

    private fun rsaKeygripInput(
        publicKey: PGPPublicKey,
    ): ByteArray {
        val key = publicKey.publicKeyPacket.key as RSAPublicBCPGKey
        // libgcrypt hashes the modulus alone, formatted as an `%m` MPI.
        return key.modulus.toLibgcryptMpi()
    }

    private fun eccKeygripInputFromEcPublicKey(
        key: ECPublicBCPGKey,
    ): ByteArray = when (val oid = key.curveOID) {
        // Ed25519 / Curve25519 store the point as 0x40||coordinate; the keygrip's q is
        // the bare 32-byte coordinate, so strip the libgcrypt native-point marker.
        GNUObjectIdentifiers.Ed25519 -> eccKeygripInput(
            curve = CURVE_ED25519,
            q = key.strippedNativePoint(fieldLength = FIELD_LENGTH_25519),
        )

        CryptlibObjectIdentifiers.curvey25519 -> eccKeygripInput(
            curve = CURVE_CURVE25519,
            q = key.strippedNativePoint(fieldLength = FIELD_LENGTH_25519),
        )

        // NIST curves store q as the uncompressed point 0x04||X||Y, hashed as-is.
        SECObjectIdentifiers.secp256r1 -> eccKeygripInput(
            curve = CURVE_NIST_P256,
            q = key.uncompressedPoint(fieldLength = FIELD_LENGTH_P256),
        )

        SECObjectIdentifiers.secp384r1 -> eccKeygripInput(
            curve = CURVE_NIST_P384,
            q = key.uncompressedPoint(fieldLength = FIELD_LENGTH_P384),
        )

        SECObjectIdentifiers.secp521r1 -> eccKeygripInput(
            curve = CURVE_NIST_P521,
            q = key.uncompressedPoint(fieldLength = FIELD_LENGTH_P521),
        )

        else -> throw IllegalArgumentException(
            "Unsupported OpenPGP curve OID for keygrip: $oid",
        )
    }

    // The 0x40-prefixed native point is (fieldLength + 1) bytes; return the coordinate
    // with the marker byte removed.
    private fun ECPublicBCPGKey.strippedNativePoint(
        fieldLength: Int,
    ): ByteArray = encodedPoint
        .toRightAlignedBytes(fieldLength + 1)
        .copyOfRange(1, fieldLength + 1)

    // The uncompressed NIST point is 0x04||X||Y, i.e. (1 + 2 * fieldLength) bytes.
    private fun ECPublicBCPGKey.uncompressedPoint(
        fieldLength: Int,
    ): ByteArray = encodedPoint.toRightAlignedBytes(1 + 2 * fieldLength)

    private fun eccKeygripInput(
        curve: EccCurve,
        q: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        appendComponent(out, 'p', curve.p)
        appendComponent(out, 'a', curve.a)
        appendComponent(out, 'b', curve.b)
        appendComponent(out, 'g', curve.g)
        appendComponent(out, 'n', curve.n)
        appendComponent(out, 'q', q)
        return out.toByteArray()
    }

    // Each keygrip component is hashed as the S-expression `(1:<c><len>:<bytes>)`, where
    // <c> is the single-letter component name and <bytes> is its minimal big-endian value.
    private fun appendComponent(
        out: ByteArrayOutputStream,
        name: Char,
        value: ByteArray,
    ) {
        out.write('('.code)
        out.write("1:".encodeToByteArray())
        out.write(name.code)
        out.write("${value.size}:".encodeToByteArray())
        out.write(value)
        out.write(')'.code)
    }

    // libgcrypt's `%m` MPI: minimal unsigned big-endian, with a leading 0x00 prepended
    // whenever the top bit of the first byte is set (so it never looks negative).
    private fun BigInteger.toLibgcryptMpi(): ByteArray {
        val magnitude = toUnsignedBytes()
        return if (magnitude.isNotEmpty() && magnitude[0].toInt() and 0x80 != 0) {
            byteArrayOf(0x00) + magnitude
        } else {
            magnitude
        }
    }

    // Right-align the minimal magnitude into a fixed-width buffer, guarding against the
    // sign byte BigInteger may add and any dropped leading zeros of the coordinate.
    private fun BigInteger.toRightAlignedBytes(
        length: Int,
    ): ByteArray {
        val magnitude = toUnsignedBytes()
        require(magnitude.size <= length) {
            "Encoded point (${magnitude.size} bytes) is longer than the expected width ($length)"
        }
        val out = ByteArray(length)
        magnitude.copyInto(out, destinationOffset = length - magnitude.size)
        return out
    }

    private fun hex(value: String): ByteArray = value.hexToByteArray()

    // The p,a,b,g,n curve constants below are libgcrypt's exact keygrip inputs (magnitudes
    // of its internal table entries), verified against `gpg --with-keygrip`.
    private class EccCurve(
        val p: ByteArray,
        val a: ByteArray,
        val b: ByteArray,
        val g: ByteArray,
        val n: ByteArray,
    )

    private const val FIELD_LENGTH_25519 = 32
    private const val FIELD_LENGTH_P256 = 32
    private const val FIELD_LENGTH_P384 = 48
    private const val FIELD_LENGTH_P521 = 66

    private val CURVE_ED25519 = EccCurve(
        p = hex("7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED"),
        // libgcrypt's table stores a = -1; the keygrip hashes the magnitude, so a = 1.
        a = hex("01"),
        // Magnitude of the table's negative d constant.
        b = hex("2DFC9311D490018C7338BF8688861767FF8FF5B2BEBE27548A14B235ECA6874A"),
        g = hex(
            "04" +
                "216936D3CD6E53FEC0A4E231FDD6DC5C692CC7609525A7B2C9562D608F25D51A" +
                "6666666666666666666666666666666666666666666666666666666666666658",
        ),
        n = hex("1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED"),
    )

    private val CURVE_CURVE25519 = EccCurve(
        p = hex("7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED"),
        // a = 121665.
        a = hex("01DB41"),
        b = hex("01"),
        g = hex(
            "04" +
                "0000000000000000000000000000000000000000000000000000000000000009" +
                "20AE19A1B8A086B4E01EDD2C7748D14C923D4D7E6D7C61B229E9C5A27ECED3D9",
        ),
        n = hex("1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED"),
    )

    private val CURVE_NIST_P256 = EccCurve(
        p = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"),
        // a = p - 3.
        a = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"),
        b = hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"),
        g = hex(
            "04" +
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296" +
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
        ),
        n = hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),
    )

    private val CURVE_NIST_P384 = EccCurve(
        p = hex(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF",
        ),
        // a = p - 3.
        a = hex(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
                "FFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFC",
        ),
        b = hex(
            "B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE814112" +
                "0314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF",
        ),
        g = hex(
            "04" +
                "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B98" +
                "59F741E082542A385502F25DBF55296C3A545E3872760AB7" +
                "3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147C" +
                "E9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F",
        ),
        n = hex(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
                "C7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973",
        ),
    )

    private val CURVE_NIST_P521 = EccCurve(
        // 2^521 - 1: minimal magnitude is 0x01 followed by 65 0xFF bytes.
        p = hex("01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"),
        // a = p - 3.
        a = hex("01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC"),
        // Minimal magnitude drops the leading 0x00 of the standard 66-byte constant.
        b = hex(
            "51953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918E" +
                "F109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46" +
                "B503F00",
        ),
        g = hex(
            "04" +
                "00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B" +
                "4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C" +
                "2E5BD66" +
                "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD1727" +
                "3E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE9476" +
                "9FD16650",
        ),
        n = hex("01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409"),
    )
}
