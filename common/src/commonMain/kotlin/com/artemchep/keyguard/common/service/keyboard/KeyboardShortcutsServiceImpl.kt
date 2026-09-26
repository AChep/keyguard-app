package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.feature.navigation.keyboard.KeyEventInterceptorRegistration
import kotlinx.collections.immutable.persistentMapOf

class KeyboardShortcutsServiceImpl(
    private val cryptoGenerator: CryptoGenerator,
) : KeyboardShortcutsService, KeyboardShortcutsServiceHost {
    private var registrations = persistentMapOf<String, KeyEventInterceptorRegistration>()

    override fun handle(keyEvent: KeyEvent): Boolean =
        registrations.any { it.value.block(keyEvent) }

    override fun register(
        block: (KeyEvent) -> Boolean,
    ): () -> Unit {
        val id = cryptoGenerator.uuid()
        val registration = KeyEventInterceptorRegistration(
            id = id,
            block = block,
        )
        registrations = registrations.put(id, registration)
        return {
            registrations = registrations.remove(id)
        }
    }
}