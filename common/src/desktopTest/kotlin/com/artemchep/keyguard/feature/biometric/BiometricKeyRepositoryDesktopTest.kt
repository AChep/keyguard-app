package com.artemchep.keyguard.feature.biometric

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiometricKeyRepositoryDesktopTest {
    @Test
    fun `linux key exists only while the keychain holds it`() = runTest {
        val keychain = FakeKeychainRepository()
        val repository = BiometricKeyRepositoryDesktop(
            keychainRepository = keychain,
            platform = Platform.Desktop.Linux(isFlatpak = false),
        )

        assertFalse(repository.exists().bind())

        keychain.put(KeychainIds.BIOMETRIC_UNLOCK.value, "key").bind()
        assertTrue(repository.exists().bind())

        repository.delete().bind()
        assertFalse(repository.exists().bind())
    }

    @Test
    fun `macos key is assumed to persist`() = runTest {
        val repository = BiometricKeyRepositoryDesktop(
            keychainRepository = FakeKeychainRepository(),
            platform = Platform.Desktop.MacOS.Jvm,
        )

        assertTrue(repository.exists().bind())
    }

    private class FakeKeychainRepository : KeychainRepository {
        private val entries = mutableMapOf<String, String>()

        override fun put(id: String, password: String, requireUserPresence: Boolean): IO<Unit> = io(Unit)
            .also { entries[id] = password }

        override fun get(id: String): IO<String> = entries[id]
            ?.let { io(it) }
            ?: ioRaise(IllegalStateException("Missing $id"))

        override fun delete(id: String): IO<Boolean> = io(entries.remove(id) != null)

        override fun contains(id: String): IO<Boolean> = io(id in entries)
    }
}
