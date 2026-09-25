package com.artemchep.keyguard.common.service.keyvalue.impl

import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonKeyValueStoreTest {
    private val json = Json

    @Test
    fun `file store writes and reads data through nested path`() = runTest {
        val root = createTempDirectory("json-store")
        val file = root.resolve("nested/preferences.json")
        val store = FileJsonKeyValueStoreStore(
            fileIo = {
                AtomicFileDestination(
                    root = root.toLocalPath(),
                    relativePath = AtomicRelativePath.fromComponents(
                        AtomicPathComponent.parse("nested"),
                        AtomicPathComponent.parse("preferences.json"),
                    ),
                )
            },
            json = json,
        )
        val state = persistentMapOf<String, Any?>(
            "name" to "alice",
            "count" to 3L,
            "enabled" to true,
        )

        store.write(state)()

        assertTrue(file.exists())
        assertEquals(state, store.read()())
    }

    @Test
    fun `json key value store falls back to empty state on malformed json`() = runTest {
        val root = createTempDirectory("json-store-malformed")
        val file = root.resolve("broken/preferences.json")
        file.parent?.toFile()?.mkdirs()
        file.writeText("{not valid json")

        val backing = FileJsonKeyValueStoreStore(
            fileIo = {
                AtomicFileDestination(
                    root = root.toLocalPath(),
                    relativePath = AtomicRelativePath.fromComponents(
                        AtomicPathComponent.parse("broken"),
                        AtomicPathComponent.parse("preferences.json"),
                    ),
                )
            },
            json = json,
        )
        val store = JsonKeyValueStore(backing)

        assertEquals(emptyMap(), store.getAll()())
    }

    @Test
    fun `clear after file deletion resets live preferences without restoring old values`() = runTest {
        val root = createTempDirectory("json-store-clear")
        val backing = FileJsonKeyValueStoreStore(
            fileIo = {
                AtomicFileDestination(
                    root = root.toLocalPath(),
                    relativePath = AtomicRelativePath.fromComponents(
                        AtomicPathComponent.parse("preferences.json"),
                    ),
                )
            },
            json = json,
        )
        val store = JsonKeyValueStore(backing)
        val customTabs = store.getString("custom_tabs", "")
        val generatorVisible = store.getBoolean("generator_visible", true)
        customTabs.setAndCommit("card,identity")()
        generatorVisible.setAndCommit(false)()
        assertEquals("card,identity", customTabs.first())
        root.resolve("preferences.json").toFile().delete()

        store.clearAndCommit()()

        assertEquals("", customTabs.first())
        assertEquals(true, generatorVisible.first())
        assertEquals(emptyMap(), backing.read()())
        store.getString("new_setting", "").setAndCommit("new")()
        assertEquals(persistentMapOf<String, Any?>("new_setting" to "new"), backing.read()())
    }

}
