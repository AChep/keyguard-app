package com.artemchep.keyguard.feature.browser

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorException
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserConnectorClientTest {
    private val report = """
        {
          "version": 1,
          "packaging": "flatpak",
          "browsers": [
            {
              "browser": "chrome",
              "status": "installed",
              "detected": true,
              "manifest": "/home/user/.config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard.json",
              "host": "/home/user/.config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard-host.sh"
            },
            {
              "browser": "edge",
              "status": "stale",
              "detected": true,
              "manifest": "C:\\Users\\user\\AppData\\Local\\Keyguard\\NativeMessagingHosts\\edge\\com.artemchep.keyguard.json",
              "registry_key": "HKEY_CURRENT_USER\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\com.artemchep.keyguard",
              "host": "C:\\Program Files\\Keyguard\\keyguard-browser-host.exe",
              "newer": true
            },
            {
              "browser": "firefox",
              "status": "browser_absent",
              "detected": false,
              "manifest": "/home/user/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json",
              "host": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheReportOfEachBrowser() {
        assertEquals(
            BrowserConnectorReport(
                packaging = BrowserConnectorPackaging.FLATPAK,
                browsers = listOf(
                    BrowserConnectorStatus(
                        browser = BrowserConnectorBrowser.CHROME,
                        state = BrowserConnectorStatus.State.INSTALLED,
                        detected = true,
                        manifest = "/home/user/.config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard.json",
                        registryKey = null,
                        host = "/home/user/.config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard-host.sh",
                    ),
                    BrowserConnectorStatus(
                        browser = BrowserConnectorBrowser.EDGE,
                        state = BrowserConnectorStatus.State.STALE,
                        detected = true,
                        manifest = "C:\\Users\\user\\AppData\\Local\\Keyguard\\NativeMessagingHosts\\edge\\" +
                            "com.artemchep.keyguard.json",
                        registryKey = "HKEY_CURRENT_USER\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\" +
                            "com.artemchep.keyguard",
                        host = "C:\\Program Files\\Keyguard\\keyguard-browser-host.exe",
                    ),
                    BrowserConnectorStatus(
                        browser = BrowserConnectorBrowser.FIREFOX,
                        state = BrowserConnectorStatus.State.BROWSER_ABSENT,
                        detected = false,
                        manifest = "/home/user/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json",
                        registryKey = null,
                        host = null,
                    ),
                ),
            ),
            parseBrowserConnectorReport(report),
        )
    }

    @Test
    fun skipsTheOutputAroundTheReport() {
        // The output has the standard error too, before and after the report.
        assertEquals(
            parseBrowserConnectorReport(report),
            parseBrowserConnectorReport("$PRELOAD_WARNING\n$report\n$PRELOAD_WARNING\n"),
        )
    }

    @Test
    fun skipsTheOutputAroundTheError() {
        val error = """{"code":"host_missing","path":"/app/keyguard-browser-host","detail":"missing"}"""
        assertEquals(
            BrowserConnectorError(
                kind = BrowserConnectorError.Kind.HOST_MISSING,
                path = "/app/keyguard-browser-host",
                detail = "missing",
            ),
            parseBrowserConnectorError("$PRELOAD_WARNING\n$error\n$PRELOAD_WARNING\n"),
        )
    }

    @Test
    fun rejectsReportsOfOtherCliVersions() {
        listOf(
            report.replace("\"version\": 1", "\"version\": 2"),
            report.replace("\"stale\"", "\"newer\""),
            report.replace("\"edge\"", "\"safari\""),
            report.replace("\"flatpak\"", "\"snap\""),
        ).forEach { raw ->
            val error = assertFailsWith<BrowserConnectorException> { parseBrowserConnectorReport(raw) }
            assertEquals(BrowserConnectorError.Kind.UPDATE_REQUIRED, error.error.kind)
        }
    }

    @Test
    fun readsCliErrorCodesAndKeepsOtherOutputAsDetail() {
        assertEquals(
            BrowserConnectorError(
                kind = BrowserConnectorError.Kind.FOREIGN_FILE,
                path = "/home/user/.mozilla/native-messaging-hosts/com.artemchep.keyguard.json",
                detail = "not written by Keyguard",
            ),
            parseBrowserConnectorError(
                """{"code":"foreign_file","path":"/home/user/.mozilla/native-messaging-hosts/""" +
                    """com.artemchep.keyguard.json","detail":"not written by Keyguard"}""",
            ),
        )
        assertEquals(
            BrowserConnectorError(
                kind = BrowserConnectorError.Kind.BROWSER_ABSENT,
                browser = BrowserConnectorBrowser.FIREFOX,
                detail = "firefox was not found",
            ),
            parseBrowserConnectorError(
                "warning\n" + """{"browser":"firefox","code":"browser_absent","detail":"firefox was not found"}""",
            ),
        )
        assertEquals(
            BrowserConnectorError(BrowserConnectorError.Kind.UNSAFE_FILE, path = "/home/user/.mozilla"),
            parseBrowserConnectorError("""{"code":"unsafe_file","path":"/home/user/.mozilla"}"""),
        )
        assertEquals(
            BrowserConnectorError(BrowserConnectorError.Kind.FAILED, detail = "Segmentation fault"),
            parseBrowserConnectorError("Segmentation fault\n"),
        )
        // An unknown code is a generic failure.
        listOf("""{"code":"newer"}""", """{"code":"invalid_origin"}""").forEach { raw ->
            assertEquals(
                BrowserConnectorError(BrowserConnectorError.Kind.FAILED, detail = raw),
                parseBrowserConnectorError(raw),
            )
        }
    }

    @Test
    fun runsTheBundledCli() = runTest {
        val directory = Files.createTempDirectory("keyguard-browser-connector")
        // A stand-in CLI: a shell script, so only where POSIX is.
        if ("posix" !in directory.fileSystem.supportedFileAttributeViews()) return@runTest
        val arguments = directory.resolve("arguments")
        val output = directory.resolve("report.json")
        output.writeText(report)
        val cli = directory.resolve("keyguard")
        cli.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > '$arguments'
            echo 'ld.so: cannot be preloaded: ignored.' >&2
            for argument; do [ "${'$'}argument" = install ] && cat '$output' && exit 0; done
            echo '{"code":"host_missing","path":"/app/keyguard-browser-host","detail":"missing"}'
            exit 1
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"))

        val installed = BundledBrowserConnectorClient(dev = true) { cli }.install(
            browsers = linkedSetOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.FIREFOX),
        )
        assertEquals(BrowserConnectorPackaging.FLATPAK, installed.packaging)
        assertEquals(
            listOf(
                "browsers", "--json", "--dev", "install",
                "--browser", "chrome", "--browser", "firefox",
            ),
            arguments.readText().lines().dropLast(1),
        )

        val error = assertFailsWith<BrowserConnectorException> { BundledBrowserConnectorClient { cli }.uninstall() }
        assertEquals(
            BrowserConnectorError(
                kind = BrowserConnectorError.Kind.HOST_MISSING,
                path = "/app/keyguard-browser-host",
                detail = "missing",
            ),
            error.error,
        )
        assertEquals(listOf("browsers", "--json", "uninstall"), arguments.readText().lines().dropLast(1))
        directory.toFile().deleteRecursively()
    }

    @Test
    fun cancellingAnOperationStopsTheCli(): Unit = runBlocking {
        val directory = Files.createTempDirectory("keyguard-browser-connector")
        // A stand-in CLI: a shell script, so only where POSIX is.
        if ("posix" !in directory.fileSystem.supportedFileAttributeViews()) return@runBlocking
        val pid = directory.resolve("pid")
        val cli = directory.resolve("keyguard")
        cli.writeText(
            """
            #!/bin/sh
            echo ${'$'}${'$'} > '$pid'
            exec sleep 60
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"))

        val client = BundledBrowserConnectorClient { cli }
        val job = launch(Dispatchers.Default) { client.status() }
        val process = withTimeout(10.seconds) {
            // Until the CLI runs.
            while (!pid.exists() || pid.readText().isBlank()) {
                delay(10.milliseconds)
            }
            ProcessHandle.of(pid.readText().trim().toLong())
        }
        // Well before the CLI's timeout.
        withTimeout(10.seconds) { job.cancelAndJoin() }
        process.ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun aBuildWithoutTheCliInstallsNoConnectors() = runTest {
        val error = assertFailsWith<BrowserConnectorException> { BundledBrowserConnectorClient { null }.status() }
        assertEquals(BrowserConnectorError.Kind.UNSUPPORTED, error.error.kind)
    }
}

/** A warning of the dynamic linker, which each process that the CLI starts may print. */
private const val PRELOAD_WARNING =
    "ERROR: ld.so: object 'libgtk3-nocsd.so.0' from LD_PRELOAD cannot be preloaded: ignored."
