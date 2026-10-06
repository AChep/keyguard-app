package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.platform.WindowId
import kotlinx.collections.immutable.persistentMapOf

class KeyboardShortcutsServiceImpl(
    private val cryptoGenerator: CryptoGenerator,
) : KeyboardShortcutsService, KeyboardShortcutsServiceHost {
    private data class Registration(
        val windowId: WindowId,
        val block: (KeyEvent) -> Boolean,
    )

    private var registrations = persistentMapOf<String, Registration>()

    override fun handle(windowId: WindowId, keyEvent: KeyEvent): Boolean =
        registrations.any { (_, registration) ->
            registration.windowId == windowId && registration.block(keyEvent)
        }

    override fun register(
        windowId: WindowId,
        block: (KeyEvent) -> Boolean,
    ): () -> Unit {
        val id = cryptoGenerator.uuid()
        val registration = Registration(
            windowId = windowId,
            block = block,
        )
        registrations = registrations.put(id, registration)
        return {
            registrations = registrations.remove(id)
        }
    }
}
