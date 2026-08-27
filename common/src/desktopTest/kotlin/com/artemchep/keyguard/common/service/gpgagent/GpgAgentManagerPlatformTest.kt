package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import org.apache.commons.lang3.SystemUtils
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpgAgentManagerPlatformTest {
    @Test
    fun `linux home uses absolute XDG data directory or persistent default`() {
        val userHome = Path.of("home/alice").toAbsolutePath()
        val dataDirectory = Path.of("data with spaces/ключі").toAbsolutePath()
        val dataHome = linuxManagedGpgHome(userHome, xdgDataHome = dataDirectory.toString())
        assertEquals(dataDirectory.resolve("keyguard/gnupg"), dataHome.path)
        assertEquals(listOf(dataHome.path.parent, dataHome.path), dataHome.ownedDirectories)

        listOf(null, "", "  ", "relative/data", "~/data").forEach { dataRoot ->
            val fallbackHome = linuxManagedGpgHome(userHome, xdgDataHome = dataRoot)
            assertEquals(userHome.resolve(".local/share/keyguard/gnupg"), fallbackHome.path)
            assertEquals(
                listOf(fallbackHome.path.parent, fallbackHome.path),
                fallbackHome.ownedDirectories,
            )
        }
    }

    @Test
    fun `linux managed homes secure only owned directories`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            val root = createTempDirectory("keyguard-gpg-linux-home")
            try {
                val home = layout.home(root)
                val ownedDirectories = layout.ownedDirectories(home)
                val sharedRoot = Files.createDirectories(ownedDirectories.first().parent)
                val rootPermissions = Files.getPosixFilePermissions(sharedRoot)
                val uid = unixUid(root)
                prepareLinuxManagedGpgHome(home, root.resolve(".gnupg"), uid, ownedDirectories)

                ownedDirectories.forEach { directory ->
                    Files.setPosixFilePermissions(directory, PosixFilePermission.entries.toSet())
                }
                prepareLinuxManagedGpgHome(home, root.resolve(".gnupg"), uid, ownedDirectories)

                ownedDirectories.forEach(::assertOwnerOnlyDirectory)
                assertOwnerOnlyFile(home.resolve("common.conf"))
                assertEquals("no-autostart\n", Files.readString(home.resolve("common.conf")))
                assertEquals(rootPermissions, Files.getPosixFilePermissions(sharedRoot))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `linux managed home supports a symlinked data root`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-linux-data-link")
        try {
            val dataRoot = Files.createDirectory(root.resolve("external-data"))
            val dataLink = root.resolve("data-link")
            Files.createSymbolicLink(dataLink, dataRoot)
            val rootPermissions = Files.getPosixFilePermissions(dataRoot)
            val home = linuxManagedGpgHome(root, xdgDataHome = dataLink.toString())

            prepareLinuxManagedGpgHome(home.path, root.resolve(".gnupg"), unixUid(root), home.ownedDirectories)

            assertEquals(dataLink.resolve("keyguard/gnupg"), home.path)
            assertOwnerOnlyDirectory(dataRoot.resolve("keyguard"))
            assertOwnerOnlyDirectory(dataRoot.resolve("keyguard/gnupg"))
            assertEquals("no-autostart\n", Files.readString(home.path.resolve("common.conf")))
            assertEquals(rootPermissions, Files.getPosixFilePermissions(dataRoot))
        } finally {
            Files.deleteIfExists(root.resolve("data-link"))
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `linux managed home does not fall back when the selected data root is unusable`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-linux-data-file")
        try {
            val dataRoot = Files.writeString(root.resolve("data"), "not a directory")
            val home = linuxManagedGpgHome(root, xdgDataHome = dataRoot.toString())

            assertFailsWith<java.io.IOException> {
                prepareLinuxManagedGpgHome(home.path, root.resolve(".gnupg"), unixUid(root), home.ownedDirectories)
            }

            assertEquals("not a directory", Files.readString(dataRoot))
            assertFalse(Files.exists(root.resolve(".local")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `linux managed home rejects an alias to the default home before modifying it`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            val root = createTempDirectory("keyguard-gpg-linux-default-link")
            try {
                val defaultHome = Files.createDirectory(root.resolve(".gnupg"))
                val defaultConfig = defaultHome.resolve("common.conf")
                Files.writeString(defaultConfig, "# user config\n")
                val defaultPermissions = Files.getPosixFilePermissions(defaultHome)
                val configPermissions = Files.getPosixFilePermissions(defaultConfig)
                val home = layout.home(root)
                Files.createDirectories(home.parent)
                Files.createSymbolicLink(home, home.parent.relativize(defaultHome))

                val error = assertFailsWith<IllegalArgumentException> {
                    prepareLinuxManagedGpgHome(home, defaultHome, unixUid(root), layout.ownedDirectories(home))
                }

                assertContains(error.message.orEmpty(), "default user GnuPG home")
                assertEquals("# user config\n", Files.readString(defaultConfig))
                assertEquals(defaultPermissions, Files.getPosixFilePermissions(defaultHome))
                assertEquals(configPermissions, Files.getPosixFilePermissions(defaultConfig))
                Files.delete(home)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `linux managed home rejects a symlinked keyguard directory`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-linux-parent-link")
        try {
            val target = Files.createDirectory(root.resolve("other-data"))
            val targetPermissions = Files.getPosixFilePermissions(target)
            val keyguard = root.resolve(".local/share/keyguard")
            Files.createDirectories(keyguard.parent)
            Files.createSymbolicLink(keyguard, target)

            val error = assertFailsWith<IllegalArgumentException> {
                prepareLinuxManagedGpgHome(
                    keyguard.resolve("gnupg"),
                    root.resolve(".gnupg"),
                    unixUid(root),
                    ownedDirectories = listOf(keyguard, keyguard.resolve("gnupg")),
                )
            }

            assertContains(error.message.orEmpty(), "symbolic link")
            assertFalse(Files.exists(target.resolve("gnupg")))
            assertEquals(targetPermissions, Files.getPosixFilePermissions(target))
            Files.delete(keyguard)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `linux managed home rejects final symlinks including dangling links`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            for (existing in listOf(false, true)) {
                val root = createTempDirectory("keyguard-gpg-linux-home-link")
                try {
                    val target = root.resolve("other-home")
                    if (existing) Files.createDirectory(target)
                    val home = layout.home(root)
                    Files.createDirectories(home.parent)
                    Files.createSymbolicLink(home, target)

                    val error = assertFailsWith<IllegalArgumentException> {
                        prepareLinuxManagedGpgHome(home, root.resolve(".gnupg"), unixUid(root), layout.ownedDirectories(home))
                    }

                    assertContains(error.message.orEmpty(), "symbolic link")
                    assertEquals(existing, Files.exists(target))
                    assertFalse(Files.exists(target.resolve("common.conf")))
                    Files.delete(home)
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `linux managed home rejects a different owner without changing permissions`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            val root = createTempDirectory("keyguard-gpg-linux-owner")
            try {
                val home = layout.home(root)
                val directory = Files.createDirectories(layout.ownedDirectories(home).first())
                val permissions = Files.getPosixFilePermissions(directory)

                val error = assertFailsWith<IllegalArgumentException> {
                    prepareLinuxManagedGpgHome(home, root.resolve(".gnupg"), unixUid(root) + 1L, layout.ownedDirectories(home))
                }

                assertContains(error.message.orEmpty(), "not owned by the current user")
                assertEquals(permissions, Files.getPosixFilePermissions(directory))
                assertFalse(Files.exists(home.resolve("common.conf")))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `linux managed home rejects reverse default aliases including missing targets`() {
        assumeTrue(supportsUnixAttributes())
        for (existing in listOf(false, true)) {
            val root = createTempDirectory("keyguard-gpg-linux-reverse-link")
            try {
                val home = root.resolve(".local/share/keyguard/gnupg")
                if (existing) Files.createDirectories(home)
                val defaultHome = root.resolve(".gnupg")
                Files.createSymbolicLink(defaultHome, home)

                val error = assertFailsWith<IllegalArgumentException> {
                    prepareLinuxManagedGpgHome(home, defaultHome, unixUid(root), ownedDirectories = listOf(home.parent, home))
                }

                assertContains(error.message.orEmpty(), "default user GnuPG home")
                assertEquals(existing, Files.exists(home))
                assertFalse(Files.exists(home.resolve("common.conf")))
                Files.delete(defaultHome)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `linux common conf rejects symbolic and hard links without changing the target`() {
        assumeTrue(supportsUnixAttributes())
        for (defaultTarget in listOf(false, true)) {
            for (hardLink in listOf(false, true)) {
                // A hard link to a non-default file is allowed; see the
                // hardlink-compatibility test below.
                if (!defaultTarget && hardLink) continue
                val root = createTempDirectory("keyguard-gpg-linux-config-link")
                try {
                    val defaultHome = Files.createDirectory(root.resolve(".gnupg"))
                    val target = if (defaultTarget) defaultHome.resolve("common.conf") else root.resolve("other.conf")
                    Files.writeString(target, "# user config\n")
                    val targetPermissions = Files.getPosixFilePermissions(target)
                    val home = Files.createDirectories(root.resolve(".local/share/keyguard/gnupg"))
                    val commonConf = home.resolve("common.conf")
                    if (hardLink) {
                        Files.createLink(commonConf, target)
                    } else {
                        Files.createSymbolicLink(commonConf, target)
                    }

                    val error = assertFailsWith<IllegalArgumentException> {
                        prepareLinuxManagedGpgHome(home, defaultHome, unixUid(root), ownedDirectories = listOf(home.parent, home))
                    }

                    val expectedMessage = if (defaultTarget) {
                        "default user GnuPG config"
                    } else {
                        "symbolic link"
                    }
                    assertContains(error.message.orEmpty(), expectedMessage)
                    assertEquals("# user config\n", Files.readString(target))
                    assertEquals(targetPermissions, Files.getPosixFilePermissions(target))
                    Files.delete(commonConf)
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `linux common conf retains regular hardlink compatibility`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-linux-config-hardlink")
        try {
            val defaultHome = Files.createDirectory(root.resolve(".gnupg"))
            val home = Files.createDirectories(root.resolve(".local/share/keyguard/gnupg"))
            val sharedConfig = root.resolve("shared.conf")
            Files.writeString(sharedConfig, "# existing\n")
            val commonConf = Files.createLink(home.resolve("common.conf"), sharedConfig)

            prepareLinuxManagedGpgHome(home, defaultHome, unixUid(root), ownedDirectories = listOf(home.parent, home))

            assertTrue(Files.isSameFile(sharedConfig, commonConf))
            assertEquals("# existing\nno-autostart\n", Files.readString(commonConf))
            assertOwnerOnlyFile(commonConf)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `linux common conf rejects reverse default aliases including missing targets`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            for (existing in listOf(false, true)) {
                val root = createTempDirectory("keyguard-gpg-linux-reverse-config-link")
                try {
                    val defaultHome = Files.createDirectory(root.resolve(".gnupg"))
                    val home = layout.home(root)
                    val commonConf = home.resolve("common.conf")
                    if (existing) {
                        Files.createDirectories(home)
                        Files.writeString(commonConf, "# existing\n")
                    }
                    val permissions = if (existing) Files.getPosixFilePermissions(commonConf) else null
                    val defaultConfig = defaultHome.resolve("common.conf")
                    Files.createSymbolicLink(defaultConfig, commonConf)

                    val error = assertFailsWith<IllegalArgumentException> {
                        prepareLinuxManagedGpgHome(home, defaultHome, unixUid(root), layout.ownedDirectories(home))
                    }

                    assertContains(error.message.orEmpty(), "default user GnuPG config")
                    assertEquals(existing, Files.exists(commonConf))
                    if (existing) {
                        assertEquals("# existing\n", Files.readString(commonConf))
                        assertEquals(permissions, Files.getPosixFilePermissions(commonConf))
                    }
                    Files.delete(defaultConfig)
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `linux native and flatpak homes preserve config and remain idempotent`() {
        assumeTrue(supportsUnixAttributes())
        for (layout in LinuxHomeLayout.entries) {
            val root = createTempDirectory("keyguard-gpg-linux-config-preserve")
            try {
                val home = layout.home(root)
                val sharedRoot = Files.createDirectories(layout.ownedDirectories(home).first().parent)
                val rootPermissions = Files.getPosixFilePermissions(sharedRoot)
                val defaultHome = Files.createDirectory(root.resolve(".gnupg"))
                val userConfig = root.resolve("user-common.conf")
                Files.writeString(userConfig, "# user config\n")
                val userConfigPermissions = Files.getPosixFilePermissions(userConfig)
                Files.createSymbolicLink(defaultHome.resolve("common.conf"), userConfig)
                val uid = unixUid(root)
                prepareLinuxManagedGpgHome(home, defaultHome, uid, layout.ownedDirectories(home))
                val commonConf = home.resolve("common.conf")
                Files.writeString(commonConf, "# existing\nallow-loopback-pinentry")

                prepareLinuxManagedGpgHome(home, defaultHome, uid, layout.ownedDirectories(home))
                prepareLinuxManagedGpgHome(home, defaultHome, uid, layout.ownedDirectories(home))

                assertEquals("# existing\nallow-loopback-pinentry\nno-autostart\n", Files.readString(commonConf))
                assertOwnerOnlyDirectory(home)
                assertOwnerOnlyFile(commonConf)
                assertEquals(rootPermissions, Files.getPosixFilePermissions(sharedRoot))
                assertEquals("# user config\n", Files.readString(userConfig))
                assertEquals(userConfigPermissions, Files.getPosixFilePermissions(userConfig))
                Files.delete(defaultHome.resolve("common.conf"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `macos release home is inside keyguard directory`() {
        val userHome = Path.of("/Users/example")

        assertEquals(
            userHome.resolve(".keyguard/gnupg"),
            macosManagedGpgHomePath(
                userHome = userHome,
                developmentHome = null,
            ),
        )
    }

    @Test
    fun `macos development home remains unchanged`() {
        val developmentHome = Path.of("/tmp/keyguard-501/gnupg")

        assertEquals(
            developmentHome,
            macosManagedGpgHomePath(
                userHome = Path.of("/Users/example"),
                developmentHome = developmentHome,
            ),
        )
    }

    @Test
    fun `managed macos home creates ancestors and tightens only owned directories`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-home")
        try {
            val userHome = root.resolve("new/user")
            val home = userHome.resolve(".keyguard/gnupg")
            val uid = unixUid(root)

            prepareMacosManagedGpgHome(home, uid)

            assertOwnerOnlyDirectory(home.parent)
            assertOwnerOnlyDirectory(home)
            val ancestors = listOf(root, userHome.parent, userHome)
            val ancestorPermissions = ancestors.associateWith { Files.getPosixFilePermissions(it) }

            Files.setPosixFilePermissions(
                home.parent,
                PosixFilePermission.entries.toSet(),
            )
            Files.setPosixFilePermissions(
                home,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                ),
            )

            prepareMacosManagedGpgHome(home, uid)

            assertOwnerOnlyDirectory(home.parent)
            assertOwnerOnlyDirectory(home)
            ancestorPermissions.forEach { (directory, permissions) ->
                assertEquals(permissions, Files.getPosixFilePermissions(directory))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos home rejects a symlink component without writing through it`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-home-symlink")
        val target = createTempDirectory("keyguard-gpg-home-target")
        val keyguardDirectory = root.resolve(".keyguard")
        try {
            Files.createDirectories(keyguardDirectory.parent)
            Files.createSymbolicLink(keyguardDirectory, target)

            val error = assertFailsWith<IllegalArgumentException> {
                prepareMacosManagedGpgHome(
                    home = keyguardDirectory.resolve("gnupg"),
                    expectedUid = unixUid(root),
                )
            }

            assertContains(error.message.orEmpty(), "symbolic link")
            assertFalse(Files.exists(target.resolve("gnupg")))
        } finally {
            Files.deleteIfExists(keyguardDirectory)
            root.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos home rejects a non-directory component`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-home-file")
        try {
            val keyguardDirectory = root.resolve(".keyguard")
            Files.createDirectories(keyguardDirectory.parent)
            Files.writeString(keyguardDirectory, "not a directory")

            val error = assertFailsWith<IllegalArgumentException> {
                prepareMacosManagedGpgHome(
                    home = keyguardDirectory.resolve("gnupg"),
                    expectedUid = unixUid(root),
                )
            }

            assertContains(error.message.orEmpty(), "not a directory")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos home rejects a directory with another owner`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-home-owner")
        try {
            val actualUid = unixUid(root)
            val error = assertFailsWith<IllegalArgumentException> {
                prepareMacosManagedGpgHome(
                    home = root.resolve(".keyguard").resolve("gnupg"),
                    expectedUid = actualUid + 1L,
                )
            }

            assertContains(error.message.orEmpty(), "not owned by the current user")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf is created owner-only`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-create")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)

            ensureUnixNoAutostart(home, uid)

            val commonConf = home.resolve("common.conf")
            assertEquals("no-autostart\n", Files.readString(commonConf))
            assertOwnerOnlyFile(commonConf)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf preserves existing content and is idempotent`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-preserve")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            val commonConf = home.resolve("common.conf")
            Files.writeString(commonConf, "# existing\nallow-loopback-pinentry")
            Files.setPosixFilePermissions(
                commonConf,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                ),
            )

            ensureUnixNoAutostart(home, uid)
            ensureUnixNoAutostart(home, uid)

            assertEquals(
                "# existing\nallow-loopback-pinentry\nno-autostart\n",
                Files.readString(commonConf),
            )
            assertOwnerOnlyFile(commonConf)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf retains regular hardlink compatibility`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-macos-common-conf-hardlink")
        try {
            val home = root.resolve(".keyguard/gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            val sharedConfig = root.resolve("shared.conf")
            Files.writeString(sharedConfig, "# existing\n")
            val commonConf = Files.createLink(home.resolve("common.conf"), sharedConfig)

            ensureUnixNoAutostart(home, uid)

            assertTrue(Files.isSameFile(sharedConfig, commonConf))
            assertEquals("# existing\nno-autostart\n", Files.readString(commonConf))
            assertOwnerOnlyFile(commonConf)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf rejects symlink without writing through`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-symlink")
        val target = Files.createTempFile("keyguard-gpg-common-conf-target", ".conf")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            Files.writeString(target, "target-content\n")
            Files.createSymbolicLink(home.resolve("common.conf"), target)

            val error = assertFailsWith<IllegalArgumentException> {
                ensureUnixNoAutostart(home, uid)
            }

            assertContains(error.message.orEmpty(), "symbolic link")
            assertEquals("target-content\n", Files.readString(target))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `managed macos common conf rejects non-regular path`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-directory")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            Files.createDirectory(home.resolve("common.conf"))

            val error = assertFailsWith<IllegalArgumentException> {
                ensureUnixNoAutostart(home, uid)
            }

            assertContains(error.message.orEmpty(), "not a regular file")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf rejects wrong owner without modifying content`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-owner")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            val commonConf = home.resolve("common.conf")
            Files.writeString(commonConf, "existing-content\n")

            val error = assertFailsWith<IllegalArgumentException> {
                ensureUnixNoAutostart(home, uid + 1L)
            }

            assertContains(error.message.orEmpty(), "not owned by the current user")
            assertEquals("existing-content\n", Files.readString(commonConf))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed macos common conf preserves malformed content on failure`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpg-common-conf-malformed")
        try {
            val home = root.resolve(".keyguard").resolve("gnupg")
            val uid = unixUid(root)
            prepareMacosManagedGpgHome(home, uid)
            val commonConf = home.resolve("common.conf")
            val malformed = byteArrayOf(0xC3.toByte(), 0x28)
            Files.write(commonConf, malformed)

            assertFailsWith<CharacterCodingException> {
                ensureUnixNoAutostart(home, uid)
            }

            assertTrue(Files.readAllBytes(commonConf).contentEquals(malformed))
            assertOwnerOnlyFile(commonConf)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `macos gpgconf prefers explicit configuration then PATH order`() {
        val configuredBin = Path.of("configured bin").toAbsolutePath()
        val pathBins = listOf("first bin", "second bin").map { Path.of(it).toAbsolutePath() }

        assertEquals(
            configuredBin.resolve("gpgconf"),
            resolveMacosGpgconf(configuredBin, pathBins, isExecutableFile = { true }),
        )
        assertEquals(
            pathBins.first().resolve("gpgconf"),
            resolveMacosGpgconf(null, pathBins, isExecutableFile = { true }),
        )
    }

    @Test
    fun `macos gpgconf searches standard installations with a minimal PATH`() {
        val pathBins = listOf(Path.of("/usr/bin"), Path.of("/bin"))
        listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/local/MacGPG2/bin",
            "/opt/local/bin",
        ).forEach { directory ->
            val executable = Path.of(directory).resolve("gpgconf").toAbsolutePath()
            assertEquals(
                executable,
                resolveMacosGpgconf(null, pathBins, isExecutableFile = { it == executable }),
            )
        }

        val error = assertFailsWith<IllegalStateException> {
            resolveMacosGpgconf(null, pathBins, isExecutableFile = { false })
        }
        assertContains(error.message.orEmpty(), "KEYGUARD_GPG_BIN_DIR")
    }

    @Test
    fun `macos gpgconf rejects invalid explicit configuration without falling back`() {
        val configuredBin = Path.of("missing bin").toAbsolutePath()
        val error = assertFailsWith<IllegalArgumentException> {
            resolveMacosGpgconf(configuredBin, listOf(Path.of("other bin"))) {
                it != configuredBin.resolve("gpgconf")
            }
        }
        assertContains(error.message.orEmpty(), configuredBin.toString())
        assertContains(error.message.orEmpty(), "executable gpgconf")
    }

    @Test
    fun `macos gpgconf requires an executable file and accepts symlinks`() {
        assumeTrue(supportsUnixAttributes())
        val root = createTempDirectory("keyguard-gpgconf-files")
        try {
            val bin = Files.createDirectory(root.resolve("bin with spaces"))
            val executable = bin.resolve("gpgconf")
            assertFailsWith<IllegalArgumentException> { resolveMacosGpgconf(bin, emptyList()) }

            Files.createDirectory(executable)
            assertFailsWith<IllegalArgumentException> { resolveMacosGpgconf(bin, emptyList()) }
            Files.delete(executable)

            Files.writeString(executable, "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(executable, setOf(PosixFilePermission.OWNER_READ))
            assertFailsWith<IllegalArgumentException> { resolveMacosGpgconf(bin, emptyList()) }
            Files.setPosixFilePermissions(
                executable,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            val linkedBin = Files.createDirectory(root.resolve("linked bin"))
            val link = Files.createSymbolicLink(linkedBin.resolve("gpgconf"), executable)
            assertEquals(link, resolveMacosGpgconf(null, listOf(linkedBin)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `macos gpgconf runner honors overrides in a JVM with a minimal PATH`() {
        assumeTrue(CurrentPlatform is Platform.Desktop.MacOS)
        val root = createTempDirectory("keyguard-gpgconf-process")
        try {
            val bin = Files.createDirectory(root.resolve("bin with spaces"))
            val executable = bin.resolve("gpgconf")
            Files.writeString(
                executable,
                """
                #!/bin/sh
                [ "$#" -eq 4 ] && [ "$1" = "--homedir" ] &&
                [ "$3" = "--list-dirs" ] && [ "$4" = "agent-socket" ] || exit 2
                printf '%s/S.gpg-agent\n' "$2"
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(
                executable,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            val home = Files.createDirectory(root.resolve("managed home with spaces"))
            val classpath = listOf(
                GpgconfRunnerProcessProbe::class.java,
                GpgconfRunner::class.java,
                Unit::class.java,
                SystemUtils::class.java,
            ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct()
                .joinToString(File.pathSeparator)

            for (useSystemProperty in listOf(false, true)) {
                val command = buildList {
                    add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                    if (useSystemProperty) add("-Dkeyguard.gpg.binDir=$bin")
                    addAll(listOf("-cp", classpath, GpgconfRunnerProcessProbe::class.java.name, home.toString()))
                }
                val outputFile = root.resolve("process-output")
                val process = ProcessBuilder(command)
                    .apply {
                        // This must be the JVM's inherited PATH, not only gpgconf's child environment.
                        environment()["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
                        environment()["KEYGUARD_GPG_BIN_DIR"] =
                            if (useSystemProperty) root.resolve("invalid env override").toString() else bin.toString()
                    }
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start()
                process.outputStream.close()
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Gpgconf runner probe timed out")
                    val output = Files.readString(outputFile).trim()
                    assertEquals(0, process.exitValue(), output)
                    assertEquals(home.resolve("S.gpg-agent").toString(), output)
                } finally {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gpgconf parser accepts raw and labeled absolute socket paths`() {
        val socket = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath()
            .normalize()
            .resolve("S.gpg-agent")
        assertEquals(
            socket,
            parseGpgconfAgentSocket("$socket\n"),
        )
        assertEquals(
            socket,
            parseGpgconfAgentSocket(
                "warning from gpgconf\n  agent-socket: $socket  \n",
            ),
        )
    }

    @Test
    fun `gpgconf parser rejects blank and relative socket paths`() {
        assertFailsWith<IllegalStateException> {
            parseGpgconfAgentSocket("\n  \n")
        }
        assertFailsWith<IllegalStateException> {
            parseGpgconfAgentSocket("relative/S.gpg-agent")
        }
        assertFailsWith<IllegalArgumentException> {
            parseGpgconfAgentSocket("agent-socket: relative/S.gpg-agent")
        }
    }

    @Test
    fun `gpgconf failure includes exit code and merged output`() {
        val message = formatGpgconfFailure(
            invocation = "gpgconf --list-dirs agent-socket",
            exitCode = 2,
            output = "stderr details\n",
        )

        assertContains(message, "exited with code 2")
        assertContains(message, "stderr details")
        assertContains(
            formatGpgconfFailure(
                invocation = "gpgconf --create-socketdir",
                exitCode = 1,
                output = "",
            ),
            "<no output>",
        )
    }

    @Test
    fun `native linux gpgconf failure aborts without a socket fallback`() {
        val cause = IllegalStateException("gpgconf discovery failed")

        val error = assertFailsWith<IllegalStateException> {
            failGpgSocketDiscovery(
                platform = Platform.Desktop.Linux.native,
                cause = cause,
            )
        }

        assertEquals(cause, error.cause)
        assertContains(error.message.orEmpty(), "standard GnuPG agent socket")
        assertContains(error.message.orEmpty(), "gpgconf --create-socketdir")
        assertContains(error.message.orEmpty(), "gpgconf discovery failed")
        assertFalse(error.message.orEmpty().contains("fallback", ignoreCase = true))
    }

    @Test
    fun `flatpak gpgconf failure reports required runtime socket access`() {
        val error = assertFailsWith<IllegalStateException> {
            failGpgSocketDiscovery(
                platform = Platform.Desktop.Linux(isFlatpak = true),
                cause = IllegalStateException("permission denied"),
            )
        }

        assertContains(error.message.orEmpty(), "Flatpak GnuPG home")
        assertContains(error.message.orEmpty(), "xdg-run/gnupg")
        assertContains(error.message.orEmpty(), "permission denied")
    }
}

// Entry point for testing the production runner in a fresh JVM environment.
internal object GpgconfRunnerProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println(GpgconfRunner().resolveAgentSocket(Path.of(args.single())))
    }
}

private fun supportsUnixAttributes(): Boolean =
    "unix" in FileSystems.getDefault().supportedFileAttributeViews()

private fun unixUid(path: Path): Long =
    (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()

private fun assertOwnerOnlyDirectory(path: Path) {
    assertTrue(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
    assertEquals(
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
    )
}

private fun assertOwnerOnlyFile(path: Path) {
    assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
    assertEquals(
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        ),
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
    )
}

private enum class LinuxHomeLayout {
    XDG_DATA,
    DEFAULT_DATA,
    FLATPAK,
    ;

    fun home(root: Path): Path = root.resolve(
        when (this) {
            XDG_DATA -> "data/keyguard/gnupg"
            DEFAULT_DATA -> ".local/share/keyguard/gnupg"
            FLATPAK -> "flatpak/data/gnupg"
        },
    )

    fun ownedDirectories(home: Path): List<Path> = when (this) {
        FLATPAK -> listOf(home)
        else -> listOf(home.parent, home)
    }
}
