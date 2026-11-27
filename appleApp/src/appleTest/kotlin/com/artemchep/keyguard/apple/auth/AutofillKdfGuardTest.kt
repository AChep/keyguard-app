package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.VaultState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class AutofillKdfGuardTest {
    private val mib = 1024L * 1024L

    private fun argon2(memoryMib: Int) = MasterKdf.Argon2id(
        iterations = 3,
        memoryKib = memoryMib * MasterKdf.Argon2id.KIB_PER_MIB,
        parallelism = 4,
    )

    private fun noMemoryQuery(): Long? = fail("The available memory must not be queried")

    @Test
    fun defaultMemoryRunsWithoutQueryingTheAvailableMemory() {
        assertTrue(isKdfWithinAutofillBudget(MasterKdf.Argon2id.DEFAULT, ::noMemoryQuery))
    }

    @Test
    fun nonArgon2DerivationsRun() {
        assertTrue(isKdfWithinAutofillBudget(MasterKdf.Pbkdf2Legacy, ::noMemoryQuery))
        assertTrue(
            isKdfWithinAutofillBudget(
                MasterKdf.Unsupported(MasterKdfVersion.V2),
                ::noMemoryQuery,
            ),
        )
    }

    @Test
    fun largerMemoryNeedsAKnownLimit() {
        assertFalse(isKdfWithinAutofillBudget(argon2(65)) { null })
    }

    @Test
    fun largerMemoryRunsOnlyWithTheMemoryPlusTheMargin() {
        val kdf = argon2(128)
        assertTrue(isKdfWithinAutofillBudget(kdf) { 160L * mib })
        assertFalse(isKdfWithinAutofillBudget(kdf) { 160L * mib - 1L })
        assertFalse(isKdfWithinAutofillBudget(kdf) { 128L * mib })
    }

    @Test
    fun noGuardKeepsTheUnlock() {
        val unlock = VaultState.Unlock.WithPassword(kdf = argon2(512)) { ioUnit() }
        assertSame(unlock, unlock.guardedBy(null))
    }

    @Test
    fun guardRefusesBeforeTheDerivationRuns() = runTest {
        var derived = false
        val unlock = VaultState.Unlock.WithPassword(kdf = argon2(512)) {
            derived = true
            ioUnit()
        }
        val result = unlock.guardedBy { false }.getCreateIo("password").attempt().bind()
        assertIs<AutofillKdfOutOfMemoryException>(result.leftOrNull())
        assertFalse(derived)
    }

    @Test
    fun guardLetsAnAllowedDerivationRun() = runTest {
        var derived = false
        val unlock = VaultState.Unlock.WithPassword(kdf = argon2(512)) {
            derived = true
            ioUnit()
        }
        val result = unlock.guardedBy { true }.getCreateIo("password").attempt().bind()
        assertTrue(result.isRight())
        assertTrue(derived)
    }
}
