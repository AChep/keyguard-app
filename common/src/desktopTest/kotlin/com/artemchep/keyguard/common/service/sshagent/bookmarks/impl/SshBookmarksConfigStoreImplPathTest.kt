package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_FILE_NAME
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SshBookmarksConfigStoreImplPathTest {
    private val home: Path = createTempDirectory("ssh-bookmarks-home")

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `refers to the directory from the home`() {
        val store = SshBookmarksConfigStoreImpl.ofPlatform(
            platform = Platform.Desktop.Windows,
            userHome = home,
        )

        // Debug builds keep their own directory.
        val name = DataDirectory.APP_NAME
        assertEquals("~/.ssh/$name", store.configDirectory)
        assertEquals(
            home.resolve(".ssh").resolve(name).resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME).toString(),
            store.includePath,
        )
    }

    @Test
    fun `doesn't check the ssh config from Flatpak`() = runTest {
        val store = SshBookmarksConfigStoreImpl.ofPlatform(
            platform = Platform.Desktop.Linux(isFlatpak = true),
            userHome = home,
        )

        assertNull(store.isIncluded())
    }

    @Test
    fun `checks the ssh config of the home`() = runTest {
        val store = SshBookmarksConfigStoreImpl.ofPlatform(
            platform = Platform.Desktop.Linux(isFlatpak = false),
            userHome = home,
        )
        assertEquals(false, store.isIncluded())

        val config = home.resolve(".ssh").resolve("config")
        Files.createDirectories(config.parent)
        config.writeText("Host *\n  IdentityAgent /tmp/agent.sock\n")
        assertEquals(false, store.isIncluded())

        config.writeText("Include \"${store.configDirectory}/$SSH_BOOKMARKS_CONFIG_FILE_NAME\"\n")
        assertEquals(true, store.isIncluded())
    }

    @Test
    fun `refers to a directory outside the home by its path`() {
        val outside = createTempDirectory("ssh-bookmarks-outside")
        try {
            assertEquals(
                outside.toString(),
                SshBookmarksConfigStoreImpl.sshConfigDirectory(
                    directory = outside,
                    userHome = home,
                ),
            )
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
