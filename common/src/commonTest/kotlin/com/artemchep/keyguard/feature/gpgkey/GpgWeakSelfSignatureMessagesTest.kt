package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_subkey_weak_self_signature_algorithms
import com.artemchep.keyguard.res.gpg_key_expiry_subkey_weak_self_signature_generic
import com.artemchep.keyguard.res.gpg_key_status_weak_self_signature_algorithms_text
import com.artemchep.keyguard.res.gpg_key_status_weak_self_signature_generic_text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgWeakSelfSignatureMessagesTest {
    @Test
    fun `key warnings preserve single and mixed digest names`() = runTest {
        listOf(
            listOf("SHA-1") to "SHA-1",
            listOf("RIPEMD-160") to "RIPEMD-160",
            listOf("SHA-1", "RIPEMD-160", "SHA-1") to "SHA-1, RIPEMD-160",
        ).forEach { (algorithms, expected) ->
            val translator = RecordingGpgTranslator()
            translator.translateGpgWeakSelfSignature(algorithms)
            assertEquals(
                listOf(Res.string.gpg_key_status_weak_self_signature_algorithms_text to listOf<Any>(expected)),
                translator.calls,
            )
        }
    }

    @Test
    fun `subkey warning reports its own binding digest names`() = runTest {
        val translator = RecordingGpgTranslator()
        translator.translateGpgSubKeyWeakSelfSignature(listOf("RIPEMD-160"))
        assertEquals(
            listOf(Res.string.gpg_key_expiry_subkey_weak_self_signature_algorithms to listOf<Any>("RIPEMD-160")),
            translator.calls,
        )
    }

    @Test
    fun `missing digest metadata uses neutral warnings without arguments`() = runTest {
        listOf(emptyList(), listOf("", " ")).forEach { algorithms ->
            val translator = RecordingGpgTranslator()
            translator.translateGpgWeakSelfSignature(algorithms)
            translator.translateGpgSubKeyWeakSelfSignature(algorithms)
            assertEquals(
                listOf(
                    Res.string.gpg_key_status_weak_self_signature_generic_text to emptyList<Any>(),
                    Res.string.gpg_key_expiry_subkey_weak_self_signature_generic to emptyList<Any>(),
                ),
                translator.calls,
            )
        }
    }
}
