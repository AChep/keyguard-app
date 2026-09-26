package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_invalid_input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgToolsInputErrorTest {
    @Test
    fun `known input parsing failures have localized feedback`() {
        val cases = mapOf(
            GpgToolsOperation.DECRYPT to listOf(
                "open_pgp_decrypt",
                "stream.update",
                "open_pgp_stream_drain",
                "stream.finish",
            ),
            GpgToolsOperation.VERIFY to listOf(
                "open_pgp_verify",
                "open_pgp_detached_verify.stream_open",
            ),
        )
        cases.forEach { (toolOperation, nativeOperations) ->
            nativeOperations.forEach { nativeOperation ->
                val failure = NativeCryptoException(nativeOperation, NativeCryptoErrorCode.INVALID_ARGUMENT)
                assertEquals(
                    Res.string.gpg_tools_invalid_input,
                    failure.gpgToolsInputErrorResource(toolOperation),
                )
            }
        }
    }

    @Test
    fun `caller setup and unrelated failures are not mislabeled as input errors`() {
        val callerFailures = listOf(
            GpgToolsOperation.DECRYPT to "open_pgp_decrypt.stream_open",
            GpgToolsOperation.DECRYPT to "unexpected_operation",
            GpgToolsOperation.VERIFY to "stream.update",
            GpgToolsOperation.VERIFY to "stream.finish",
            GpgToolsOperation.VERIFY to "open_pgp_clear_verify.stream_open",
        )
        callerFailures.forEach { (toolOperation, nativeOperation) ->
            val failure = NativeCryptoException(nativeOperation, NativeCryptoErrorCode.INVALID_ARGUMENT)
            assertNull(failure.gpgToolsInputErrorResource(toolOperation))
        }

        val verifyFailure = NativeCryptoException("open_pgp_verify", NativeCryptoErrorCode.INVALID_ARGUMENT)
        assertNull(verifyFailure.gpgToolsInputErrorResource(GpgToolsOperation.SIGN))
        assertNull(verifyFailure.gpgToolsInputErrorResource(GpgToolsOperation.ENCRYPT))
        val limit = NativeCryptoException("open_pgp_stream_drain", NativeCryptoErrorCode.RESOURCE_LIMIT)
        assertNull(limit.gpgToolsInputErrorResource(GpgToolsOperation.DECRYPT))
        assertNull(IllegalStateException("No key selected").gpgToolsInputErrorResource(GpgToolsOperation.DECRYPT))
    }
}
