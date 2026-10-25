package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.SshCertificate
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeSshCertificate
import com.artemchep.keyguard.nativecrypto.NativeSshCertificateOption
import com.artemchep.keyguard.nativecrypto.NativeSshCertificateType

object NativeSshCertificateParser : SshCertificateParser {
    override fun parse(
        certificate: String,
        publicKey: String?,
    ): SshCertificate? {
        val certificateOpenSsh = certificate.trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return try {
            NativeCrypto.ssh.describeCertificate(
                certificateOpenSsh = certificateOpenSsh,
                publicKeyOpenSsh = publicKey?.trim()?.takeIf { it.isNotEmpty() },
            ).toDomain()
        } catch (_: NativeCryptoException) {
            null
        } catch (_: IllegalArgumentException) {
            // The certificate or the key exceeds the size limit.
            null
        }
    }
}

private fun NativeSshCertificate.toDomain() = SshCertificate(
    type = type.toDomain(),
    keyId = keyId,
    serial = serial,
    principals = principals,
    validAfter = validAfter,
    validBefore = validBefore,
    caFingerprint = caFingerprint,
    criticalOptions = criticalOptions.map { it.toDomain() },
    extensions = extensions.map { it.toDomain() },
    publicKeyMatches = publicKeyMatches,
)

private fun NativeSshCertificateType.toDomain(): SshCertificate.Type = when (this) {
    NativeSshCertificateType.USER -> SshCertificate.Type.User
    NativeSshCertificateType.HOST -> SshCertificate.Type.Host
}

private fun NativeSshCertificateOption.toDomain() = SshCertificate.Option(
    name = name,
    value = value,
)
