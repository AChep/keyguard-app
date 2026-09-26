package com.artemchep.keyguard.di

import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.PhoneCredentialProviderPlatformConfig
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.copy.LogRepositoryAndroid
import com.artemchep.keyguard.createPhoneKoinApplication
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactory
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactoryAndroid
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

class PhoneKoinGraphTest {
    @Test
    fun `phone graph assembles without overrides and keeps vault services scoped`() {
        val application = createPhoneKoinApplication(
            application = { error("Safe graph checks must not access Android services") },
            sdkInt = 34,
        )
        try {
            val koin = application.koin
            assertSame(PhoneCredentialProviderPlatformConfig, koin.get<CredentialProviderPlatformConfig>())
            assertSame(VaultRouteFactoryDefault, koin.get<VaultRouteFactory>())
            assertSame(ScanQrRouteFactoryAndroid, koin.get<ScanQrRouteFactory>())
            assertSame(koin.get<LogRepositoryAndroid>(), koin.get<PlatformLogSinkRegistry>().values.single())
            assertNotNull(koin.get<CoroutineDispatcher>(named<DatabaseDispatcher>()))
            assertFailsWith<NoDefinitionFoundException> { koin.get<GetCiphers>() }
        } finally {
            application.close()
        }
    }

    @Test
    fun `older Android graph omits credential capabilities and worker`() {
        val application = createPhoneKoinApplication(
            application = { error("Safe graph checks must not access Android services") },
            sdkInt = 33,
        )
        try {
            assertNull(application.koin.getOrNull<CredentialProviderPlatformConfig>())
            assertTrue(application.koin.get<WorkerRegistry>().values.isEmpty())
        } finally {
            application.close()
        }
    }
}
