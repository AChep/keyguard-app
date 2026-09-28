package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCertificateMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddKeyGenerationTest {
    @Test
    fun sshReplacementRequiresExplicitUseAndPreservesBothKeys() {
        val original = ssh("original")
        val generated = ssh("generated")
        var draft: GetPasswordResult = original
        val session = AddKeyGeneration(AddItemKind.SSH_KEY) { draft = it; true }
        session.generate()
        session.update(true, generated)
        assertSame(original, draft)
        assertTrue(session.use())
        assertSame(generated, draft)
        assertFalse(session.use())
    }

    @Test
    fun gpgResultRetainsTheFullTypedPayload() {
        val metadata = GpgAgentKeyMetadata(listOf(GpgAgentCertificateMetadata("fingerprint")))
        val generated = GetPasswordResult.AsyncGpgKey(
            GeneratedGpgKey("private", "public", "fingerprint", metadata, "Test <test@example.com>", "Ed25519"),
        )
        var draft: GetPasswordResult? = null
        val session = AddKeyGeneration(AddItemKind.GPG_KEY) { draft = it; true }
        session.generate()
        session.update(true, generated)
        assertTrue(session.canUse)
        assertTrue(session.use())
        assertSame(generated, draft)
        assertSame(metadata, (draft as GetPasswordResult.AsyncGpgKey).gpgKey.metadata)
    }

    @Test
    fun cancelledStepRejectsLateResultsAndLeavesDraftUnchanged() {
        var draft: GetPasswordResult? = null
        val session = AddKeyGeneration(AddItemKind.SSH_KEY) { draft = it; true }
        session.generate()
        session.close()
        session.update(true, ssh("late"))
        assertFalse(session.canUse)
        assertFalse(session.use())
        assertNull(draft)
    }

    @Test
    fun configurationChangesAndRegenerationCannotReapplyThePreviousResult() {
        val previous = ssh("previous")
        val session = AddKeyGeneration(AddItemKind.SSH_KEY) { true }
        session.generate()
        session.update(true, previous)
        session.invalidate()
        session.update(true, previous)
        assertFalse(session.canUse)
        session.update(true, ssh("lateAfterOptionChange"))
        assertFalse(session.canUse)
        session.generate()
        session.update(false, previous)
        assertFalse(session.canUse)
        session.update(true, ssh("new"))
        assertTrue(session.canUse)
    }

    @Test
    fun wrongTypeFailureAndUnavailableDraftCannotBeApplied() {
        val session = AddKeyGeneration(AddItemKind.GPG_KEY) { false }
        session.generate()
        session.update(true, ssh("wrongType"))
        assertFalse(session.canUse)
        session.update(true, GetPasswordResult.Value(""))
        assertFalse(session.use())
        val unavailable = AddKeyGeneration(AddItemKind.SSH_KEY) { false }
        unavailable.generate()
        unavailable.update(true, ssh("generated"))
        assertFalse(unavailable.use())
    }

    private fun ssh(fingerprint: String): GetPasswordResult.AsyncKey {
        val type = KeyPair.Type.ED25519
        return GetPasswordResult.AsyncKey(
            KeyPair(
                type,
                KeyPair.KeyParameter(byteArrayOf(1), type, "private", fingerprint),
                KeyPair.KeyParameter(byteArrayOf(2), type, "public", fingerprint),
            ),
        )
    }
}
