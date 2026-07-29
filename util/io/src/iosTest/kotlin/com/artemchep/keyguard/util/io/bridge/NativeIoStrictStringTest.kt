package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.NativeErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeIoStrictStringTest {
    /**
     * A path that cannot be encoded as UTF-8 is rejected before the C bridge,
     * which validates its input with `str::from_utf8`.
     *
     * The rejection is reported as the ABI's own invalid-argument scalar
     * rather than as a thrown exception, so this bridge and the JNI bridge
     * answer identically for identical input. Throwing here would bypass the
     * caller's decode path and surface a different exception type on Apple
     * targets only.
     */
    @Test
    fun malformedUtf16PathIsRejectedBeforeTheCBridge() {
        val result = NativeIo.directoryOpen("\uD800")

        assertEquals(NATIVE_IO_BRIDGE_INVALID_ARGUMENT, result)

        val decoded = decodeNativeIoFailure(result)
        assertEquals(NativeIoOperation.Bridge, decoded.operation)
        assertEquals(FileSystemFailureKind.InvalidInput, decoded.failure.kind)
        assertEquals(
            NativeErrorDomain.Bridge,
            decoded.failure.diagnostic?.domain,
        )
    }
}
