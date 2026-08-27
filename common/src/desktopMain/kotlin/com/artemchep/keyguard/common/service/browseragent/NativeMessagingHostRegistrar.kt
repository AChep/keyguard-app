package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files

/**
 * Writes the Native Messaging host manifests for Firefox, Chrome, and Edge
 * so that each browser can spawn the agent via `connectNative()`.
 *
 * The manifest is written once at startup if the binary exists and differs
 * from the on-disk version (content-addressed).  If the binary is missing
 * (dev build, no package yet), registration is silently skipped.
 */
class NativeMessagingHostRegistrar(
    private val logRepository: LogRepository,
) {
    companion object {
        private const val TAG = "NativeMessagingHostRegistrar"

        private const val HOST_ID = "com.artemchep.keyguard_agent"
        private const val BINARY_BASE_NAME = "keyguard-browser-agent"

        // Chrome / Edge
        private val CHROME_MANIFEST_DIR = Path.of(
            System.getProperty("user.home"),
            ".config/google-chrome/NativeMessagingHosts",
        )
        private val EDGE_MANIFEST_DIR = Path.of(
            System.getProperty("user.home"),
            ".config/microsoft-edge/NativeMessagingHosts",
        )

        // Firefox — profile-based; we detect the active profile from profiles.ini.
        // CachyOS (and some other distros) use ~/.config/mozilla/firefox/ instead
        // of the standard ~/.mozilla/firefox/. We check both locations.
        // However, NM manifests are always in ~/.mozilla/native-messaging-hosts/
        // regardless of the profile directory location.
        private val FIREFOX_MANIFEST_DIR = Path.of(
            System.getProperty("user.home"),
            ".mozilla/native-messaging-hosts",
        )
    }

    /**
     * Finds the agent binary and writes NM manifests for all detected browsers.
     *
     * @param binaryPath The absolute path to the agent binary, or null if not found.
     */
    fun register(binaryPath: Path?) {
        if (binaryPath == null) {
            logRepository.post(TAG, "Agent binary not found — skipping NM registration", LogLevel.DEBUG)
            return
        }

        val binaryAbs = binaryPath.toAbsolutePath().toString()

        // Chrome / Edge use "allowed_origins" with chrome-extension:// prefix.
        val chromeManifest = buildManifestChrome(binaryAbs)
        // Firefox uses "allowed_extensions" with plain extension ID.
        val firefoxManifest = buildManifestFirefox(binaryAbs)

        val chromeDirs = listOf(CHROME_MANIFEST_DIR, EDGE_MANIFEST_DIR)
        for (dir in chromeDirs) {
            writeManifest(dir, chromeManifest)
        }
        writeManifest(FIREFOX_MANIFEST_DIR, firefoxManifest)
    }

    private fun writeManifest(dir: Path, manifest: String) {
        try {
            Files.createDirectories(dir)
            val target = dir.resolve("$HOST_ID.json")
            val existing = if (Files.exists(target)) Files.readString(target) else ""
            if (existing != manifest) {
                Files.writeString(target, manifest)
                logRepository.post(TAG, "NM manifest written: $target", LogLevel.INFO)
            } else {
                logRepository.post(TAG, "NM manifest up-to-date: $target", LogLevel.DEBUG)
            }
        } catch (e: Exception) {
            logRepository.post(TAG, "Failed to write NM manifest to $dir: ${e.message}", LogLevel.WARNING)
        }
    }

    /** Chrome / Edge manifest — uses "allowed_origins". */
    private fun buildManifestChrome(binaryPath: String): String = buildJsonObject {
        put("name", HOST_ID)
        put("description", "Keyguard Browser Agent")
        put("path", binaryPath)
        put("type", "stdio")
        putJsonArray("args") {
            add("--native-messaging")
        }
        putJsonArray("allowed_origins") {
            addJsonObject { put("extension_id", "keyguard-browser-agent@keyguard.app") }
        }
    }.toString()

    /** Firefox manifest — uses "allowed_extensions". */
    private fun buildManifestFirefox(binaryPath: String): String = buildJsonObject {
        put("name", HOST_ID)
        put("description", "Keyguard Browser Agent")
        put("path", binaryPath)
        put("type", "stdio")
        putJsonArray("allowed_extensions") {
            add("keyguard-browser-agent@keyguard.app")
        }
    }.toString()
}
