package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpOperationKind
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenPgpOutputPolicyTest {
    @Test
    fun `operation kinds map to compatibility-preserving output policies`() {
        val expected = mapOf(
            GpgOpenPgpOperationKind.CHECK_PERMISSION to OpenPgpOutputPolicy.NONE,
            GpgOpenPgpOperationKind.GET_SIGN_KEY_ID to OpenPgpOutputPolicy.NONE,
            GpgOpenPgpOperationKind.GET_KEY_IDS to OpenPgpOutputPolicy.NONE,
            GpgOpenPgpOperationKind.GET_KEY to OpenPgpOutputPolicy.REQUIRED,
            GpgOpenPgpOperationKind.CLEAR_SIGN to OpenPgpOutputPolicy.REQUIRED,
            GpgOpenPgpOperationKind.DETACHED_SIGN to OpenPgpOutputPolicy.NONE,
            GpgOpenPgpOperationKind.ENCRYPT to OpenPgpOutputPolicy.REQUIRED,
            GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT to OpenPgpOutputPolicy.REQUIRED,
            GpgOpenPgpOperationKind.DECRYPT_VERIFY to OpenPgpOutputPolicy.OPTIONAL,
            GpgOpenPgpOperationKind.DECRYPT_METADATA to OpenPgpOutputPolicy.DISCARD,
            GpgOpenPgpOperationKind.AUTOCRYPT_STATUS to OpenPgpOutputPolicy.NONE,
        )

        assertEquals(
            GpgOpenPgpOperationKind.entries.toSet(),
            expected.keys,
            "Every operation kind must declare its output contract.",
        )
        expected.forEach { (kind, policy) ->
            assertEquals(policy, openPgpOutputPolicy(kind), kind.name)
        }
    }
}
