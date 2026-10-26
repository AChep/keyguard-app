package com.artemchep.keyguard.common.service.shortcut

interface GlobalShortcutRecording {
    /** Always release, including when a recorder leaves composition or loses its window. */
    suspend fun release()
}
