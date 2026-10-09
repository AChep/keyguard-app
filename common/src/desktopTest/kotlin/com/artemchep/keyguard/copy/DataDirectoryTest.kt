package com.artemchep.keyguard.copy

import net.harawata.appdirs.AppDirsFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataDirectoryTest {
    // Moving the data directory makes existing vaults and settings disappear.
    @Test
    fun dataDirectoryMatchesTheAppDirsLayout() {
        val expected = AppDirsFactory.getInstance()
            .getUserDataDir(DataDirectory.APP_NAME, null, DataDirectory.APP_AUTHOR)

        assertEquals(expected, DataDirectory().dataBlocking())
    }

    @Test
    fun relativePlatformDirectoryIsRejected() {
        listOf("", "relative/share").forEach { platformDirectory ->
            assertFailsWith<IllegalArgumentException>(message = platformDirectory) {
                DataDirectory().appDirectory(platformDirectory)
            }
        }
    }
}
