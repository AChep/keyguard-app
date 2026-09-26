package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.dsl.module

class AndroidIpcModuleTest {
    @Test
    fun `registration service uses the same repository instance`() {
        val repository = AndroidIpcRegistrationRepository(
            store = JsonKeyValueStore(),
            json = Json,
        )
        // This fixture verifies only the alias; application roots validate the complete graph.
        val koin = Koin()
        koin.loadModules(
            listOf(AndroidIpcModule().module, module {
                single<AndroidIpcRegistrationRepository> { repository }
            }),
            allowOverride = true,
        )

        try {
            assertSame(
                expected = repository,
                actual = koin.get<AndroidIpcRegistrationService>(),
            )
        } finally {
            koin.close()
        }
    }
}
