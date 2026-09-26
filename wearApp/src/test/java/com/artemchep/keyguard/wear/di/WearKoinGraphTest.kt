package com.artemchep.keyguard.wear.di

import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.wear.createWearKoinApplication
import com.artemchep.keyguard.wear.credential.WearCredentialProviderPlatformConfig
import com.artemchep.keyguard.wear.feature.navigation.AttachmentPreviewRouteFactoryWear
import com.artemchep.keyguard.wear.feature.navigation.VaultRouteFactoryWear
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.qualifier.named

class WearKoinGraphTest {
    @Test
    fun `wear graph selects wear routes and capabilities without overrides`() {
        val application = createWearKoinApplication(
            application = { error("Safe graph checks must not access Android services") },
            sdkInt = 34,
        )
        try {
            val koin = application.koin
            assertSame(WearCredentialProviderPlatformConfig, koin.get<CredentialProviderPlatformConfig>())
            assertSame(VaultRouteFactoryWear, koin.get<VaultRouteFactory>())
            assertSame(AttachmentPreviewRouteFactoryWear, koin.get<AttachmentPreviewRouteFactory>())
            assertSame(PermissionsSettingsRouteFactoryDefault, koin.get<PermissionsSettingsRouteFactory>())
            assertSame(AndroidIpcRegistrationServiceNone, koin.get<AndroidIpcRegistrationService>())
            assertNotNull(koin.get<CoroutineDispatcher>(named<DatabaseDispatcher>()))
            assertFailsWith<NoDefinitionFoundException> { koin.get<GetCiphers>() }
        } finally {
            application.close()
        }
    }

    @Test
    fun `older Wear graph omits credential capabilities and worker`() {
        val application = createWearKoinApplication(
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
