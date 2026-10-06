package com.artemchep.keyguard.feature.fido2

import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Fido2PromptTest {
    @Test
    fun cancellationDiscardsLateSecretsAndCompletesOnlyOnce() {
        var completions = 0
        val prompt = Fido2Prompt(Fido2Operation.Register(ByteArray(32), ByteArray(32))) {
            completions++
        }
        prompt.cancel()
        val lateSecret = ByteArray(32) { 7 }
        prompt.complete(Result.success(lateSecret))
        prompt.cancel()
        assertEquals(1, completions)
        assertFalse(prompt.active.value)
        assertContentEquals(ByteArray(32), lateSecret)
    }
}
