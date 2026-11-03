package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import com.artemchep.keyguard.common.service.keyvalue.impl.DefaultJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutPersistenceRollbackException
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.platform.Platform
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryGlobalShortcutsTest {
    private fun repository(store: JsonKeyValueStore = JsonKeyValueStore()) =
        SettingsRepositoryImpl(store, Json, Base64ServiceImpl())

    @Test fun `missing settings preserve enabled platform defaults`() =
        runTest {
            val config = repository().getGlobalShortcuts().first()
            for (action in GlobalShortcutAction.entries) {
                assertTrue(config[action].enabled)
                val key =
                    if (action ==
                        GlobalShortcutAction.QuickSearch
                    ) {
                        GlobalShortcutKey.Space
                    } else {
                        GlobalShortcutKey.L
                    }
                assertEquals(
                    GlobalShortcutBinding(key, shift = true, meta = true),
                    config[action].resolve(action, Platform.Desktop.MacOS.Jvm),
                )
                for (platform in listOf(Platform.Desktop.Windows, Platform.Desktop.Linux.native)) {
                    assertEquals(
                        GlobalShortcutBinding(key, ctrl = true, shift = true),
                        config[action].resolve(action, platform),
                    )
                }
            }
        }

    @Test fun `disabled custom binding round trips through new repository and backup restore`() =
        runTest {
            val store = JsonKeyValueStore()
            val original = repository(store)
            val custom =
                GlobalShortcutsConfig(
                    quickSearch = GlobalShortcutConfig(false, GlobalShortcutBinding(GlobalShortcutKey.F8, alt = true)),
                )
            original.setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom.quickSearch).invoke()
            assertEquals(custom, repository(store).getGlobalShortcuts().first())
            val restored = repository()
            restored.restore(original.backup().invoke()).invoke()
            assertEquals(custom, restored.getGlobalShortcuts().first())
            restored
                .setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom.quickSearch.copy(binding = null))
                .invoke()
            assertFalse(
                restored
                    .getGlobalShortcuts()
                    .first()
                    .quickSearch.enabled,
            )
            assertNull(
                restored
                    .getGlobalShortcuts()
                    .first()
                    .quickSearch.binding,
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `failed disk write does not publish or retain a provisional shortcut`() =
        runTest {
            val store =
                JsonKeyValueStore(
                    object : JsonKeyValueStoreStore by DefaultJsonKeyValueStoreStore() {
                        override fun write(state: PersistentMap<String, Any?>): IO<Unit> = { error("disk full") }
                    },
                )
            val repo = repository(store)
            val observed = mutableListOf<GlobalShortcutsConfig>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                repo.getGlobalShortcuts().collect { observed.add(it) }
            }
            val custom = GlobalShortcutConfig(enabled = false)
            // The store kept its value, so the original error is reported without a rollback.
            val failure =
                assertFailsWith<IllegalStateException> {
                    repo.setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom).invoke()
                }
            assertEquals("disk full", failure.message)
            runCurrent()
            assertEquals(GlobalShortcutsConfig(), repo.getGlobalShortcuts().first())
            assertEquals(listOf(GlobalShortcutsConfig()), observed)
            val backupSource = repository()
            backupSource.setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom).invoke()
            val shortcutBackup = backupSource.backup().invoke().filterKeys { it == "keyboard.global_shortcuts" }
            val restoreFailure = assertFailsWith<IllegalStateException> { repo.restore(shortcutBackup).invoke() }
            assertEquals("disk full", restoreFailure.message)
            runCurrent()
            assertEquals(GlobalShortcutsConfig(), repo.getGlobalShortcuts().first())
            assertEquals(listOf(GlobalShortcutsConfig()), observed)
        }

    @Test fun `failed write that still reached the disk is rolled back`() =
        runTest {
            var disk = persistentMapOf<String, Any?>()
            var failures = 0
            val store =
                JsonKeyValueStore(
                    object : JsonKeyValueStoreStore {
                        override fun read(): IO<PersistentMap<String, Any?>> = { disk }

                        override fun write(state: PersistentMap<String, Any?>): IO<Unit> =
                            {
                                disk = state
                                if (failures-- > 0) error("sync failed")
                            }
                    },
                )
            val repo = repository(store)
            val custom = GlobalShortcutConfig(enabled = false)

            failures = 1
            val failure =
                assertFailsWith<IllegalStateException> {
                    repo.setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom).invoke()
                }
            assertEquals("sync failed", failure.message)
            assertEquals(GlobalShortcutsConfig(), repo.getGlobalShortcuts().first())

            failures = 2
            assertFailsWith<GlobalShortcutPersistenceRollbackException> {
                repo.setGlobalShortcut(GlobalShortcutAction.QuickSearch, custom).invoke()
            }
        }

    @Test fun `key serialization uses the key name and rejects unknown values`() {
        val original = GlobalShortcutBinding(GlobalShortcutKey.Y, ctrl = true)
        val encoded = Json.encodeToString(original)
        assertTrue(encoded.contains("\"key\":\"Y\""))
        assertEquals(original, Json.decodeFromString<GlobalShortcutBinding>(encoded))
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Json.decodeFromString<GlobalShortcutBinding>("""{"key":"KeyY","ctrl":true}""")
        }
    }

    @Test fun `unknown persisted keys cannot activate defaults`() =
        runTest {
            val store = JsonKeyValueStore()
            store
                .getString("keyboard.global_shortcuts", "{}")
                .setAndCommit(
                    """{"quick_search":{"binding":{"key":"KeyY","ctrl":true}}}""",
                ).invoke()
            val repo = repository(store)
            val config = repo.getGlobalShortcuts().first()
            assertFalse(config.quickSearch.enabled)
            assertFalse(config.lockVault.enabled)
            // Replacing the unreadable entry must not turn the missing one into a default.
            repo.setGlobalShortcut(GlobalShortcutAction.QuickSearch, GlobalShortcutConfig()).invoke()
            assertEquals(
                GlobalShortcutsConfig(lockVault = GlobalShortcutConfig(enabled = false)),
                repo.getGlobalShortcuts().first(),
            )
        }

    @Test fun `unreadable entry only disables its action and survives editing another`() =
        runTest {
            val store = JsonKeyValueStore()
            val raw = store.getString("keyboard.global_shortcuts", "{}")
            val quickSearch = """{"binding":{"key":"KeyY","ctrl":true}}"""
            raw
                .setAndCommit("""{"quick_search":$quickSearch,"lock_vault":{"binding":{"key":"F8","alt":true}}}""")
                .invoke()
            val repo = repository(store)
            val lockVault = GlobalShortcutConfig(binding = GlobalShortcutBinding(GlobalShortcutKey.F8, alt = true))
            assertEquals(
                GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(enabled = false), lockVault = lockVault),
                repo.getGlobalShortcuts().first(),
            )

            repo.setGlobalShortcut(GlobalShortcutAction.LockVault, lockVault.copy(enabled = false)).invoke()
            assertTrue(raw.first().contains(""""quick_search":$quickSearch"""))
            assertEquals(lockVault.copy(enabled = false), repo.getGlobalShortcuts().first().lockVault)
        }
}
