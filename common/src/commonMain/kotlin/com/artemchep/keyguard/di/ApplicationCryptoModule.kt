package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyEditorImportReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.googleauthenticator.OtpMigrationService
import com.artemchep.keyguard.common.service.googleauthenticator.impl.OtpMigrationServiceImpl
import com.artemchep.keyguard.common.service.googleauthenticator.util.OtpMigrationParser
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.GpgKeyserverClientImpl
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.service.totp.impl.TotpServiceImpl
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshIntervalVariants
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.impl.GetAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgKeyserverAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgKeyserverConfigImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgKeyserverLastRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgKeyserverRefreshIntervalImpl
import com.artemchep.keyguard.common.usecase.impl.GetGpgKeyserverRefreshIntervalVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetTotpCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetTotpCodeWithOffsetImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgKeyserverAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgKeyserverConfigImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgKeyserverLastRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.PutGpgKeyserverRefreshIntervalImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.NativeGpgUserIdReplacementService
import com.artemchep.keyguard.crypto.NativeGpgUserIdRevocationService
import com.artemchep.keyguard.crypto.NativeKeyPairGenerator
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import com.artemchep.keyguard.util.zip.ZipService
import com.artemchep.keyguard.util.zip.createZipService
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class ApplicationCryptoModule {
    val module = module {
        single<NativeCryptoGenerator>() bind CryptoGenerator::class

        single<NativeCipherEncryptor>() bind CipherEncryptor::class

        single<NativeKeyPairGenerator>() bind KeyPairGenerator::class

        single<PasskeyCrypto> {
            NativePasskeyCrypto
        }

        single<SshKeyImportService> {
            NativeSshKeyImportService
        }

        single<GpgPublicKeyParser> {
            NativeGpgPublicKeyParser
        }

        single<GpgCertificateMaterialReconciler> {
            NativeGpgCertificateMaterialReconciler
        }

        single<GpgUserIdRevocationService> {
            NativeGpgUserIdRevocationService
        }

        single<GpgUserIdReplacementService> {
            NativeGpgUserIdReplacementService
        }

        single<GpgKeyMetadataResolver> {
            NativeGpgKeyMetadataResolver
        }

        single {
            GpgKeyEditorImportReconciler(
                materialReconciler = get(),
                metadataResolver = get(),
                publicKeyParser = get(),
            )
        }

        single<GetTotpCodeImpl>() bind GetTotpCode::class

        single<GetTotpCodeWithOffsetImpl>() bind GetTotpCodeWithOffset::class

        single<GetAutofillCopyTotpImpl>() bind GetAutofillCopyTotp::class

        single<PutGpgKeyserverConfigImpl>() bind PutGpgKeyserverConfig::class

        single<PutGpgKeyserverLastRefreshImpl>() bind PutGpgKeyserverLastRefresh::class

        single<PutGpgKeyserverAutoRefreshImpl>() bind PutGpgKeyserverAutoRefresh::class

        single<PutGpgKeyserverRefreshIntervalImpl>() bind PutGpgKeyserverRefreshInterval::class

        single<GetGpgKeyserverConfigImpl>() bind GetGpgKeyserverConfig::class

        single<GetGpgKeyserverAutoRefreshImpl>() bind GetGpgKeyserverAutoRefresh::class

        single<GetGpgKeyserverRefreshIntervalImpl>() bind GetGpgKeyserverRefreshInterval::class

        single<GetGpgKeyserverRefreshIntervalVariantsImpl>() bind GetGpgKeyserverRefreshIntervalVariants::class

        single<GetGpgKeyserverLastRefreshImpl>() bind GetGpgKeyserverLastRefresh::class

        single<GpgKeyserverClient> {
            GpgKeyserverClientImpl(
                httpClient = get(),
                parser = get(),
                json = get(),
            )
        }

        single<PutAutofillCopyTotpImpl>() bind PutAutofillCopyTotp::class

        single<ZipService> { createZipService() }

        single<OtpMigrationServiceImpl>() bind OtpMigrationService::class

        single<OtpMigrationParser>()

        single<TotpServiceImpl>() bind TotpService::class
    }
}
