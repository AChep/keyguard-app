package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.toHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes a session file that the Native Messaging host (launched by the browser)
 * can read to connect to the IPC server without receiving the auth token on
 * stdin (which Firefox occupies with NM JSON).
 *
 * The file is written to `~/.config/keyguard/agent-session.json` with mode
 * 0600 so only the same user can read it.
 */
class AgentSessionFileWriter(
    private val logRepository: LogRepository,
) {
    companion object {
        private const val TAG = "AgentSessionFileWriter"

        private val SESSION_PATH: Path = Path.of(
            System.getProperty("user.home"),
            ".config", "keyguard", "agent-session.json",
        )

        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class SessionData(
        @SerialName("auth_token")
        val authToken: String,
        @SerialName("ipc_socket")
        val ipcSocket: String,
    )

    /**
     * Writes the session file with the auth token and IPC socket path.
     * Called by the Kotlin app when it starts the NM-hosted agent.
     */
    fun write(authToken: ByteArray, ipcSocketPath: String) {
        try {
            val dir = SESSION_PATH.parent
            Files.createDirectories(dir)

            val data = SessionData(
                authToken = authToken.toHex(),
                ipcSocket = ipcSocketPath,
            )
            val jsonStr = json.encodeToString(SessionData.serializer(), data)
            Files.writeString(SESSION_PATH, jsonStr)

            // Restrict permissions to owner-only (0600).
            try {
                Files.setPosixFilePermissions(
                    SESSION_PATH,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows doesn't support POSIX permissions; skip.
            }

            logRepository.post(TAG, "Session file written: $SESSION_PATH", LogLevel.INFO)
        } catch (e: Exception) {
            logRepository.post(TAG, "Failed to write session file: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Deletes the session file. Called on shutdown.
     */
    fun delete() {
        try {
            Files.deleteIfExists(SESSION_PATH)
            logRepository.post(TAG, "Session file deleted", LogLevel.INFO)
        } catch (e: Exception) {
            logRepository.post(TAG, "Failed to delete session file: ${e.message}", LogLevel.WARNING)
        }
    }
}
