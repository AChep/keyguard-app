package com.artemchep.keyguard.desktop.instance

import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.util.instance.InstanceException
import com.artemchep.keyguard.util.instance.InstanceFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstanceFailureTest {
    @Test
    fun permissionAndMissingLibraryFailuresDoNotSuggestClosingAnotherProcess() {
        assertEquals(
            Res.string.instance_service_permission_text,
            instanceFailureMessage(InstanceFailureKind.PERMISSION),
        )
        assertEquals(
            Res.string.instance_service_unavailable_text,
            instanceFailureMessage(InstanceFailureKind.UNAVAILABLE),
        )
        assertEquals(Res.string.instance_service_timeout_text, instanceFailureMessage(InstanceFailureKind.TIMEOUT))
    }

    @Test
    fun visibleDetailsPreserveSafeNativeFieldsWithoutLeakingExceptionCauses() {
        val diagnostic = "kind=Io operation=accept_activation_client io_kind=TooManyOpenFiles os_code=24"
        val failure = InstanceException(
            kind = InstanceFailureKind.IO,
            cause = IllegalStateException("/private/user/path token=secret"),
            diagnostic = diagnostic,
        )
        assertEquals(diagnostic, instanceFailureDetails(failure))
        assertEquals("kind=PERMISSION", instanceFailureDetails(InstanceException(InstanceFailureKind.PERMISSION)))
        val unexpected = instanceFailureDetails(IllegalStateException("/private/user/path token=secret"))
        assertFalse(unexpected.contains("private"))
        assertFalse(unexpected.contains("secret"))
    }
}
