package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.dragout.PreparedContentStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.common.service.dragout.readAllBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.module

class PreparedContentModuleTest {
    @Test
    fun `first resolving content from a vault does not bind its lifetime to that vault`() = runTest {
        val rootWindow = object : WindowCoroutineScope {
            override val coroutineContext = backgroundScope.coroutineContext
        }
        val koin = Koin()
        koin.loadModules(
            listOf(
                ApplicationTransfersModule().module,
                VaultOperationsModule().module,
                module {
                    single<WindowCoroutineScope> { rootWindow }
                    single<LogRepository> { RecordingLogRepository() }
                    single<ShowMessage> { MessageHubImpl() }
                },
            ),
        )
        try {
            val session = KoinVaultSessionFactory(koin).create(
                MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
            )
            try {
                // Deliberately resolve the singleton from the vault before the root.
                val vaultScope = checkNotNull(session.resolve { get<WindowCoroutineScope>() })
                val store = checkNotNull(session.resolve { get<PreparedContentStore>() })
                assertSame(store, koin.get<PreparedContentStore>())
                val owned = store.reserve(owner = vaultScope.coroutineContext.job).publish("owned.txt", payload())
                store.reserve().publish("attachment.txt", payload()).use { content ->
                    content.handoff()
                    owned.handoff()
                    session.close()
                    // Locking retires what the vault prepared; the store itself outlives the vault.
                    assertFalse(owned.isAvailable())
                    assertEquals("payload", content.readAllBytes().decodeToString())
                }
            } finally {
                session.close()
            }
        } finally {
            koin.close()
        }
    }

    private fun payload() = TestByteSnapshot("payload".encodeToByteArray())
}
