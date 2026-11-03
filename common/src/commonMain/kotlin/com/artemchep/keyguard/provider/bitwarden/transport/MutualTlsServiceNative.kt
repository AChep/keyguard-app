package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.nativecrypto.NativeCryptoPkcs12
import com.artemchep.keyguard.nativecrypto.NativePkcs12Identity
import com.artemchep.keyguard.nativecrypto.NativePkcs12Result
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlin.io.encoding.Base64
import kotlin.time.Instant

class MutualTlsServiceNative : MutualTlsService {
    override fun importPkcs12(sourcePkcs12: ByteArray, password: String?): MutualTlsImportResult =
        when (val result = NativeCryptoPkcs12.process(sourcePkcs12, password)) {
            is NativePkcs12Result.Success -> try {
                MutualTlsImportResult.Success(
                    imported = ServerEnv.MutualTls.ImportedPkcs12(
                        pkcs12Base64 = Base64.Default.encode(result.identity.bundle),
                    ),
                    metadata = result.identity.toMetadata(),
                )
            } finally {
                result.identity.bundle.fill(0)
            }
            NativePkcs12Result.PasswordRequired -> MutualTlsImportResult.PasswordRequired
            NativePkcs12Result.InvalidPassword -> MutualTlsImportResult.InvalidPassword
            NativePkcs12Result.Invalid -> MutualTlsImportResult.Error
        }

    override fun describeImportedPkcs12(pkcs12Base64: String): MutualTlsCertificateMetadata? {
        val bytes = try {
            Base64.Default.decode(pkcs12Base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val result = try {
            NativeCryptoPkcs12.process(bytes, normalize = false)
        } finally {
            bytes.fill(0)
        }
        return (result as? NativePkcs12Result.Success)?.identity?.toMetadata()
    }
}

private fun NativePkcs12Identity.toMetadata() = MutualTlsCertificateMetadata(
    displayName = displayName,
    issuer = issuer,
    expiresAt = Instant.fromEpochSeconds(expiresAt),
    subject = subject,
    issuerName = issuerCommonName.ifEmpty { issuer },
    validFrom = Instant.fromEpochSeconds(notBefore),
    serialNumber = serialNumber.toColonHex(),
    sha256Fingerprint = sha256Fingerprint.toColonHex(),
    keyType = keyType,
)

private val ColonHexFormat = HexFormat {
    upperCase = true
    bytes.byteSeparator = ":"
}

private fun ByteArray.toColonHex() = toHexString(ColonHexFormat)
