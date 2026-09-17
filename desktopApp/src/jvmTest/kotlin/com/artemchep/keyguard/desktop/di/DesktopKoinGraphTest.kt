package com.artemchep.keyguard.desktop.di

import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.createDesktopKoinApplication
import com.artemchep.keyguard.di.ApplicationCoroutineScope
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

class DesktopKoinGraphTest {
    @Test
    fun `desktop graph assembles without overrides and preserves aliases and qualifiers`() {
        val application = createDesktopKoinApplication()
        try {
            val koin = application.koin
            assertSame(koin.get<DataDirectory>(), koin.get<DirsService>())
            assertSame(koin.get<LogRepositoryKotlin>(), koin.get<PlatformLogSinkRegistry>().values.single())
            assertSame(AndroidIpcRegistrationServiceNone, koin.get<AndroidIpcRegistrationService>())
            assertSame(VaultRouteFactoryDefault, koin.get<VaultRouteFactory>())
            assertTrue(koin.get<PlatformLinkInfoExtractorRegistry>().values.isEmpty())
            assertNotNull(koin.get<CoroutineDispatcher>(named<DatabaseDispatcher>()))
            assertFailsWith<NoDefinitionFoundException> { koin.get<GetCiphers>() }
        } finally {
            application.close()
        }
    }

    @Test
    fun `closing the application cancels the application coroutine scope`() {
        val application = createDesktopKoinApplication()
        val scope = try {
            application.koin.get<CoroutineScope>(named<ApplicationCoroutineScope>())
                .also { assertTrue(it.isActive) }
        } finally {
            application.close()
        }
        assertFalse(scope.isActive)
    }

    @Test
    fun `separate application roots own separate singleton instances`() {
        val first = createDesktopKoinApplication()
        val second = createDesktopKoinApplication()
        try {
            assertNotSame(first.koin.get<DataDirectory>(), second.koin.get<DataDirectory>())
            assertNotSame(first.koin.get<InMemoryLogRepository>(), second.koin.get<InMemoryLogRepository>())
        } finally {
            second.close()
            first.close()
        }
    }

}
