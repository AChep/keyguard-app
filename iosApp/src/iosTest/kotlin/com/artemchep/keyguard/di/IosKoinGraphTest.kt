package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.extract.PlatformLinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.logging.PlatformLogSinkRegistry
import com.artemchep.keyguard.common.service.logging.kotlin.LogRepositoryKotlin
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.createIosKoinApplication
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

class IosKoinGraphTest {
    @Test
    fun `native vault scopes preserve runtime keys and retire independently`() {
        val application = createIosKoinApplication()
        try {
            val factory = application.koin.get<VaultSessionFactory>()
            val key = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
            val first = factory.create(key)
            val second = factory.create(key)
            val rootWindow = application.koin.get<WindowCoroutineScope>()
            val firstWindow = assertNotNull(first.resolve { get<WindowCoroutineScope>() })
            val secondWindow = assertNotNull(second.resolve { get<WindowCoroutineScope>() })

            assertSame(key, first.resolve { get<MasterKey>() })
            assertSame(key, second.resolve { get<MasterKey>() })
            assertNotEquals(first.id, second.id)
            assertNotSame(firstWindow, secondWindow)
            assertNotSame(rootWindow, firstWindow)

            first.retire()
            assertNull(first.resolve { get<MasterKey>() })
            first.close()
            first.close()
            assertFalse(firstWindow.coroutineContext[Job]!!.isActive)
            assertTrue(secondWindow.coroutineContext[Job]!!.isActive)
            assertTrue(rootWindow.coroutineContext[Job]!!.isActive)

            application.close()
            assertFalse(second.active.value)
            assertFalse(secondWindow.coroutineContext[Job]!!.isActive)
        } finally {
            application.close()
        }
    }

    @Test
    fun `iOS graph assembles without overrides or eager unsupported services`() {
        val application = createIosKoinApplication()
        try {
            val koin = application.koin
            assertSame(AndroidIpcRegistrationServiceNone, koin.get<AndroidIpcRegistrationService>())
            assertSame(koin.get<LogRepositoryKotlin>(), koin.get<PlatformLogSinkRegistry>().values.single())
            assertSame(VaultRouteFactoryDefault, koin.get<VaultRouteFactory>())
            assertTrue(koin.get<PlatformLinkInfoExtractorRegistry>().values.isEmpty())
            assertTrue(koin.get<WorkerRegistry>().values.isEmpty())
            assertNotNull(koin.get<CoroutineDispatcher>(named<DatabaseDispatcher>()))
            assertFailsWith<NoDefinitionFoundException> { koin.get<GetCiphers>() }
        } finally {
            application.close()
        }
    }
}
