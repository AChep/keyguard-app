package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_FILE_NAME
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_MARKER
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksConfig
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksSummary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@Suppress("TooManyFunctions")
class SshBookmarksConfigStoreImplTest {
    private val home: Path = createTempDirectory("ssh-bookmarks-home")
    private val directory: Path = home.resolve(".ssh").resolve("keyguard")
    private val store = SshBookmarksConfigStoreImpl(
        directory = directory,
        configDirectory = "~/.ssh/keyguard",
    )

    private val isPosix = Files.getFileStore(home)
        .supportsFileAttributeView("posix")

    private val keyA = "a".repeat(64) + ".pub"
    private val keyB = "b".repeat(64) + ".pub"
    private val certB = "c".repeat(64) + "-cert.pub"

    private val generated = "$SSH_BOOKMARKS_CONFIG_MARKER for tests.\n"

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `writes owner-only files and prunes stale ones`() = runTest {
        store.replaceAll(
            config(
                SSH_BOOKMARKS_CONFIG_FILE_NAME to "config-1\n",
                keyA to "key-a\n",
                keyB to "key-b\n",
                certB to "cert-b\n",
            ),
        )
        // A file of the user is never removed.
        directory.resolve("notes.txt").writeText("mine")

        store.replaceAll(
            config(
                SSH_BOOKMARKS_CONFIG_FILE_NAME to "config-2\n",
                keyA to "key-a\n",
            ),
        )

        assertEquals(
            setOf(SSH_BOOKMARKS_CONFIG_FILE_NAME, keyA, "notes.txt"),
            directory.listDirectoryEntries().map { it.name }.toSet(),
        )
        assertEquals("config-2\n", directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME).readText())
        assertEquals(SshBookmarksSummary(hosts = 1, skipped = 2), store.summary.value)
        if (isPosix) {
            assertEquals("rw-------", permissions(directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME)))
            assertEquals("rw-------", permissions(directory.resolve(keyA)))
            assertEquals("rwx------", permissions(directory))
        }
    }

    @Test
    fun `restores the mode of an unchanged file`() = runTest {
        if (!isPosix) {
            return@runTest
        }
        val config = config(SSH_BOOKMARKS_CONFIG_FILE_NAME to "config\n")
        store.replaceAll(config)
        val path = directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-r--"))

        store.replaceAll(config)

        assertEquals("rw-------", permissions(path))
    }

    @Test
    fun `an access list is owner-only if only the owner is allowed`() {
        val owner = UserPrincipal { "me" }
        val other = UserPrincipal { "Users" }
        fun entry(type: AclEntryType, principal: UserPrincipal) = AclEntry.newBuilder()
            .setType(type)
            .setPrincipal(principal)
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA)
            .build()

        assertTrue(isOwnerOnlyAcl(owner, listOf(entry(AclEntryType.ALLOW, owner))))
        assertTrue(
            isOwnerOnlyAcl(
                owner,
                listOf(entry(AclEntryType.ALLOW, owner), entry(AclEntryType.DENY, other)),
            ),
        )
        // For example, an entry inherited from the home directory.
        assertFalse(
            isOwnerOnlyAcl(
                owner,
                listOf(entry(AclEntryType.ALLOW, owner), entry(AclEntryType.ALLOW, other)),
            ),
        )
    }

    @Test
    fun `replaces a file that is not UTF-8`() = runTest {
        val config = config(SSH_BOOKMARKS_CONFIG_FILE_NAME to generated)
        store.replaceAll(config)
        val path = directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME)
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))

        store.replaceAll(config)

        assertEquals(generated, path.readText())
    }

    @Test
    fun `clear removes the directory once it is empty`() = runTest {
        store.replaceAll(config(SSH_BOOKMARKS_CONFIG_FILE_NAME to generated, keyA to "key-a\n"))

        store.clear()

        assertFalse(directory.exists())
        assertTrue(directory.parent.exists())
        assertNull(store.summary.value)
    }

    @Test
    fun `clear keeps the files of the user`() = runTest {
        store.replaceAll(config(SSH_BOOKMARKS_CONFIG_FILE_NAME to generated, keyA to "key-a\n"))
        directory.resolve("notes.txt").writeText("mine")

        store.clear()

        assertEquals(listOf("notes.txt"), directory.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `clear keeps a configuration that Keyguard did not write`() = runTest {
        Files.createDirectories(directory)
        val path = directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME)
        path.writeText("Host mine\n")
        directory.resolve(keyA).writeText("key-a\n")

        store.clear()

        assertEquals(listOf(SSH_BOOKMARKS_CONFIG_FILE_NAME), directory.listDirectoryEntries().map { it.name })
        assertEquals("Host mine\n", path.readText())
    }

    @Test
    fun `clear keeps a directory without generated files`() = runTest {
        Files.createDirectories(directory)

        store.clear()

        assertTrue(directory.exists())
    }

    @Test
    fun `clear without a directory does nothing`() = runTest {
        store.clear()

        assertFalse(directory.exists())
    }

    private fun config(vararg files: Pair<String, String>) = SshBookmarksConfig(
        files = files.toMap(),
        summary = SshBookmarksSummary(hosts = 1, skipped = 2),
    )

    private fun permissions(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
}
