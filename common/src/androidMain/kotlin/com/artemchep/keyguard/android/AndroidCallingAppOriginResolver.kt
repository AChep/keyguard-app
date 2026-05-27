package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import com.artemchep.keyguard.common.exception.credential.CallingAppNotPrivilegedException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddPrivilegedAppRequest
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.util.toHex
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal class AndroidCallingAppOriginResolver(
    private val cryptoService: CryptoGenerator,
    private val privilegedAppsService: PrivilegedAppsService,
) {
    @RequiresApi(Build.VERSION_CODES.P)
    fun privilegedRequest(
        appInfo: CallingAppInfo,
    ): AddPrivilegedAppRequest {
        val cert = callingAppCertificate(appInfo)
            .toHexFingerprint(cryptoService)
        return AddPrivilegedAppRequest(
            packageName = appInfo.packageName,
            certFingerprintSha256 = cert,
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun origin(
        appInfo: CallingAppInfo,
        privilegedApps: List<DPrivilegedApp>,
    ): String {
        val origin = kotlin.runCatching {
            getPrivilegedOrigin(
                appInfo = appInfo,
                privilegedApps = privilegedApps,
            )
        }
            .getOrElse { e ->
                if (!appInfo.isOriginPopulated()) {
                    throw e
                }
                throw CallingAppNotPrivilegedException()
            }
            ?: kotlin.run {
                val cert = callingAppCertificate(appInfo)
                cert.toOrigin(cryptoService)
            }
        return origin
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun callingAppCertificate(
        appInfo: CallingAppInfo,
    ): CallingAppCertificate {
        val signatureBytes = appInfo.signingInfo.apkContentsSigners[0].toByteArray()

        val certFactory = CertificateFactory.getInstance("X509")
        val cert = signatureBytes
            .inputStream()
            .use {
                certFactory.generateCertificate(it) as X509Certificate
            }

        return CallingAppCertificate(cert.encoded)
    }

    private suspend fun getPrivilegedOrigin(
        appInfo: CallingAppInfo,
        privilegedApps: List<DPrivilegedApp>,
    ): String? {
        val privilegedAllowlist = privilegedAppsService.stringify(privilegedApps)
            .bind()
        return appInfo.getOrigin(privilegedAllowlist)
    }

    private data class CallingAppCertificate(
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CallingAppCertificate

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

        fun toOrigin(
            cryptoService: CryptoGenerator,
        ): String {
            val certHash = cryptoService.hashSha256(data)
            val certB64 = PasskeyBase64.encodeToString(certHash)
            return "android:apk-key-hash:$certB64"
        }

        fun toHexFingerprint(
            cryptoService: CryptoGenerator,
        ): String {
            val certHash = cryptoService.hashSha256(data)
            return certHash
                .toHex()
                .uppercase()
                .chunked(2)
                .joinToString(separator = ":")
        }
    }
}
