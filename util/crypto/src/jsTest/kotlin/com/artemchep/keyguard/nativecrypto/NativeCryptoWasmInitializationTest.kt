package com.artemchep.keyguard.nativecrypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class NativeCryptoWasmInitializationTest {
    @Test
    fun initializationIsRepeatableBeforeSynchronousCalls() = runTest {
        initializeNativeCryptoWasm()
        initializeNativeCryptoWasm()
        assertContentEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray(),
            NativeCrypto.primitives.sha256("abc".encodeToByteArray()),
        )
    }
}
