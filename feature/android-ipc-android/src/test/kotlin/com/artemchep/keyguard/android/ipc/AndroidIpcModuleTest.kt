package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.test.Test
import kotlin.test.assertSame

class AndroidIpcModuleTest {
    @Test
    fun `registration service overrides the common fallback`() {
        val repository = AndroidIpcRegistrationRepository(
            store = JsonKeyValueStore(),
            json = Json,
        )
        val di = DI {
            bindSingleton<AndroidIpcRegistrationService> {
                AndroidIpcRegistrationServiceNone
            }
            import(
                module = androidIpcModule(),
                allowOverride = true,
            )
            bindSingleton<AndroidIpcRegistrationRepository>(overrides = true) {
                repository
            }
        }

        assertSame(
            expected = repository,
            actual = di.direct.instance<AndroidIpcRegistrationService>(),
        )
    }
}
