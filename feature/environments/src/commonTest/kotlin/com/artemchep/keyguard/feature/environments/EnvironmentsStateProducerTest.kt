package com.artemchep.keyguard.feature.environments

import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentAccount
import com.artemchep.keyguard.common.service.environment.EnvironmentApprovalRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentCatalog
import com.artemchep.keyguard.common.service.environment.EnvironmentDocument
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.service.environment.EnvironmentMcpState
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsState
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.feature.environments.ui.EnvironmentEditor
import com.artemchep.keyguard.feature.environments.ui.EnvironmentsEffect
import com.artemchep.keyguard.feature.environments.ui.EnvironmentsState
import com.artemchep.keyguard.feature.environments.ui.environmentsStateProducer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnvironmentsStateProducerTest {
    private class Producer(
        private val scope: TestScope,
        private val state: StateFlow<EnvironmentsState>,
        val effects: List<EnvironmentsEffect>,
    ) {
        val value: EnvironmentsState
            get() {
                scope.runCurrent()
                return state.value
            }

        val editor: EnvironmentEditor
            get() = assertNotNull(value.editor)

        val draft: EnvironmentEditorState?
            get() = value.editor?.draft

        fun act(block: EnvironmentsState.() -> Unit) {
            value.block()
            scope.runCurrent()
        }

        fun edit(block: EnvironmentEditor.() -> Unit) {
            editor.block()
            scope.runCurrent()
        }
    }

    private fun TestScope.producer(
        service: FakeService,
        verifyAccess: suspend () -> Boolean = { false },
    ): Producer {
        val effects = mutableListOf<EnvironmentsEffect>()
        val state = environmentsStateProducer(service, service, service.mounts, service.mcp, verifyAccess, effects::add)
            .stateIn(backgroundScope, SharingStarted.Eagerly, EnvironmentsState())
        return Producer(this, state, effects)
    }

    private fun Producer.importText(text: String) = edit {
        onImportTextChange!!(text)
        onPreviewImport!!()
        onApplyImport!!()
    }

    @Test
    fun lockClearsSecretDraftAndImportPreview() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.edit { onNameChange!!("Development") }
        producer.importText("TOKEN=secret")
        val editor = producer.editor
        assertTrue(editor.draft.canSave)
        assertEquals("secret", editor.literalValue(0))
        val unlocked = producer.value
        service.state.value = service.state.value.copy(unlocked = false)
        // Native/Compose click events may still refer to the previous rendered state.
        assertNull(editor.literalValue(0))
        assertNull(producer.draft)
        assertNull(producer.value.onCreate)
        unlocked.onCreate!!()
        assertNull(producer.draft)
    }

    @Test
    fun latePickerCannotWriteIntoANewDraftAfterLock() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        val stale = producer.editor
        service.state.value = service.state.value.copy(unlocked = false)
        producer.value
        service.state.value = service.state.value.copy(unlocked = true)
        producer.act { onCreate!!() }
        stale.onNameChange!!("stale")
        stale.onAddReference!!(reference, "TOKEN")
        assertEquals(EnvironmentEditorState(accountId = "account"), producer.draft)
    }

    @Test
    fun sessionReplacementClearsDraftEvenWhenLockedStateIsConflated() = runTest {
        val service = FakeService()
        service.state.value = service.state.value.copy(sessionVersion = "first")
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.edit { onNameChange!!("Development") }
        producer.importText("TOKEN=secret")
        val stale = producer.editor
        service.state.value = service.state.value.copy(unlocked = false, sessionVersion = null)
        service.state.value = service.state.value.copy(unlocked = true, sessionVersion = "second")
        assertNull(stale.literalValue(0))
        stale.onNameChange!!("stale edit")
        assertNull(producer.draft)
    }

    @Test
    fun discardedLoadCannotRestoreSecretStateAfterCancellation() = runTest {
        val service = FakeService()
        service.loadGate = CompletableDeferred()
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        producer.act { onClose!!() }
        service.loadGate!!.complete(Unit)
        assertNull(producer.draft)
        assertFalse(producer.value.busy)
    }

    @Test
    fun deniedVerificationNeverLoadsTheSecret() = runTest {
        val service = FakeService().apply { challenge = "session-bound" }
        val producer = producer(service, verifyAccess = { false })
        producer.act { onOpen!!("item") }
        assertFalse(service.loaded)
        assertNull(producer.draft)
    }

    @Test
    fun deniedVerificationReportsDenial() = runTest {
        val service = FakeService().apply { challenge = "session-bound" }
        val producer = producer(service, verifyAccess = { false })
        producer.act { onOpen!!("item") }
        assertEquals(listOf<EnvironmentsEffect>(EnvironmentsEffect.Denied), producer.effects)
    }

    @Test
    fun newDraftTakesTheWholeOwnershipAndSavesIt() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.edit { onNameChange!!("Development") }
        val ownership = EnvironmentOwnership(
            folder = EnvironmentFolder.New("Work"),
            organizationId = "organization",
            collectionIds = setOf("collection"),
        )
        producer.edit { onOwnershipChange!!("other", ownership) }
        assertEquals("other", producer.draft?.accountId)
        assertEquals(ownership, producer.draft?.ownership)
        producer.edit { onSave!!() }
        assertEquals(ownership, service.savedOwnership)
    }

    @Test
    fun referencesKeepTheirAccountWhenAnOwnershipResultTriesToMoveTheDraft() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.edit { onNameChange!!("Development") }
        producer.edit { onAddReference!!(reference, "TOKEN") }
        val original = producer.draft!!
        assertFalse(original.canChangeAccount)

        val ownership = EnvironmentOwnership(folder = EnvironmentFolder.Id("other-folder"))
        producer.edit { onOwnershipChange!!("other", ownership) }

        assertEquals(original, producer.draft)
        producer.edit { onSave!!() }
        assertEquals("account", service.savedAccountId)
        assertEquals(original.definition, service.savedDefinition)
    }

    @Test
    fun sameAccountOwnershipChangesPreserveReferencesAndPendingFieldPicker() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        producer.edit { onAddReference!!(reference, "TOKEN") }
        val picker = producer.editor
        val ownership = EnvironmentOwnership(
            folder = EnvironmentFolder.Id("folder"),
            organizationId = "organization",
            collectionIds = setOf("collection"),
        )

        producer.edit { onOwnershipChange!!("account", ownership) }
        picker.onAddReference!!(reference, "OTHER_TOKEN")

        assertEquals(ownership, producer.draft?.ownership)
        assertEquals(listOf("TOKEN", "OTHER_TOKEN"), producer.draft?.definition?.variables?.map { it.name })
        assertEquals(reference, producer.draft?.definition?.variables?.first()?.source)
    }

    @Test
    fun removingTheLastReferenceAllowsAnAccountChangeWithoutLosingOtherVariables() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        producer.edit { onAddReference!!(reference, "TOKEN") }
        producer.edit { onAddReference!!(reference, "OTHER_TOKEN") }
        producer.edit {
            onAddValue!!()
            onVariableNameChange!!(2, "VALUE")
            onVariableValueChange!!(2, "literal")
        }
        producer.edit { onRemoveVariable!!(0) }
        assertFalse(producer.draft!!.canChangeAccount)

        producer.edit { onRemoveVariable!!(0) }
        assertTrue(producer.draft!!.canChangeAccount)
        producer.edit { onOwnershipChange!!("other", EnvironmentOwnership()) }

        assertEquals("other", producer.draft?.accountId)
        assertEquals("literal", producer.editor.literalValue(0))
    }

    @Test
    fun explicitlyReplacingAReferenceAllowsAnAccountChange() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        producer.edit { onAddReference!!(reference, "TOKEN") }
        producer.edit { onVariableSourceChange!!(0, EnvironmentValueSource.Unset) }
        assertTrue(producer.draft!!.canChangeAccount)

        producer.edit { onOwnershipChange!!("other", EnvironmentOwnership()) }

        assertEquals("other", producer.draft?.accountId)
        assertEquals(EnvironmentValueSource.Unset, producer.draft?.definition?.variables?.single()?.source)
    }

    @Test
    fun changingAccountInvalidatesPendingFieldPickerEvenAfterSwitchingBack() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        val picker = producer.editor

        producer.edit { onOwnershipChange!!("other", EnvironmentOwnership()) }
        producer.edit { onOwnershipChange!!("account", EnvironmentOwnership()) }
        picker.onAddReference!!(reference, "STALE")
        assertEquals(emptyList(), producer.draft?.definition?.variables)

        producer.edit { onAddReference!!(reference, "TOKEN") }
        assertEquals(listOf("TOKEN"), producer.draft?.definition?.variables?.map { it.name })
        // Other actions still belong to this draft.
        picker.onNameChange!!("Development")
        assertEquals("Development", producer.draft?.name)
    }

    @Test
    fun fieldPickerCannotWriteIntoAReplacedDraft() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        val picker = producer.editor

        producer.act { onCreate!!() }
        picker.onAddReference!!(reference, "TOKEN")

        assertEquals(emptyList(), producer.draft?.definition?.variables)
    }

    @Test
    fun existingDraftOnlyChangesItsFolder() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        producer.edit { onVariableReferenceChange!!(0, reference, "PASSWORD") }
        val folder = EnvironmentFolder.Id("folder")
        producer.edit {
            onOwnershipChange!!(
                "other",
                EnvironmentOwnership(
                    folder = folder,
                    organizationId = "organization",
                    collectionIds = setOf("collection"),
                ),
            )
        }
        val draft = producer.draft!!
        assertEquals("account", draft.accountId)
        assertEquals(EnvironmentOwnership(folder = folder), draft.ownership)
        // A named variable keeps its name.
        assertEquals(EnvironmentVariable("TOKEN", reference), draft.definition.variables.single())
        assertFalse(draft.canChangeAccount)
    }

    @Test
    fun pickedReferenceNamesAnUnnamedVariable() = runTest {
        val producer = producer(FakeService())
        producer.act { onCreate!!() }
        producer.edit { onAddValue!!() }
        producer.edit { onVariableReferenceChange!!(0, reference, "PASSWORD") }
        assertEquals(EnvironmentVariable("PASSWORD", reference), producer.draft?.definition?.variables?.single())
    }

    @Test
    fun saveReportsTheSavedIdOnlyAfterSuccess() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        // An unnamed draft cannot be saved.
        assertNull(producer.editor.onSave)
        producer.edit { onNameChange!!("Development") }
        producer.edit { onSave!!() }
        assertEquals(listOf<EnvironmentsEffect>(EnvironmentsEffect.Saved("created")), producer.effects)
        assertEquals("created", producer.draft?.id)
    }

    @Test
    fun trashReportsCompletionAndClearsTheDraft() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        producer.edit { onTrash!!() }
        assertEquals(listOf<EnvironmentsEffect>(EnvironmentsEffect.Trashed), producer.effects)
        assertNull(producer.draft)
    }

    @Test
    fun saveConfirmsAccessAgainOnceItWasRevoked() = runTest {
        val service = FakeService().apply { challenge = "session-bound" }
        var verifications = 0
        val producer = producer(service, verifyAccess = {
            verifications += 1
            true
        })
        producer.act { onOpen!!("item") }
        assertEquals(1, verifications)
        // For example, the permissions of the item have changed.
        service.granted = false
        producer.edit { onSave!!() }
        assertEquals(2, verifications)
        assertEquals(listOf<EnvironmentsEffect>(EnvironmentsEffect.Saved("item")), producer.effects)
    }

    @Test
    fun busyOperationDisablesEdits() = runTest {
        val service = FakeService()
        service.loadGate = CompletableDeferred()
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        assertTrue(producer.value.busy)
        assertNull(producer.value.onMcpEnabledChange)
        service.loadGate!!.complete(Unit)
        assertFalse(producer.value.busy)
        service.saveGate = CompletableDeferred()
        val idle = producer.editor
        producer.edit { onSave!!() }
        assertTrue(producer.value.busy)
        assertNull(producer.editor.onNameChange)
        assertNull(producer.editor.onSave)
        // A captured action still checks it.
        idle.onNameChange!!("stale")
        assertEquals("Development", producer.draft?.name)
        service.saveGate!!.complete(Unit)
        assertFalse(producer.value.busy)
        assertNotNull(producer.editor.onNameChange)
    }

    @Test
    fun replacingTheDraftNeverInterruptsAWrite() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        service.saveGate = CompletableDeferred()
        producer.edit { onSave!!() }
        producer.act { onOpen!!("other") }
        producer.act { onCreate!!() }
        producer.act { onClose!!() }
        assertTrue(producer.value.busy)
        assertEquals("item", producer.draft?.id)
        service.saveGate!!.complete(Unit)
        assertFalse(producer.value.busy)
        assertFalse(producer.value.failed)
        assertEquals(listOf<EnvironmentsEffect>(EnvironmentsEffect.Saved("item")), producer.effects)
        // Loading is interrupted.
        service.loadGate = CompletableDeferred()
        producer.act { onOpen!!("other") }
        producer.act { onCreate!!() }
        assertNull(producer.draft?.id)
        service.loadGate!!.complete(Unit)
        assertNull(producer.draft?.id)
        assertFalse(producer.value.busy)
    }

    @Test
    fun moveVariableReordersVariablesAndConcealsValues() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.importText("A=1\nB=2\nC=3")
        producer.edit { onRevealToggle(0) }
        producer.edit { onMoveVariable!!(0, 2) }
        val draft = producer.draft!!
        assertEquals(listOf("B", "C", "A"), draft.definition.variables.map { it.name })
        assertTrue(draft.revealed.isEmpty())
        // Out of range moves are ignored.
        producer.edit { onMoveVariable!!(2, 3) }
        assertEquals(listOf("B", "C", "A"), producer.draft!!.definition.variables.map { it.name })
    }

    @Test
    fun sourceChangesNeverWriteUnsupportedSourcesOrUnpickedReferences() = runTest {
        val service = FakeService()
        val producer = producer(service)
        producer.act { onCreate!!() }
        producer.edit { onAddReference!!(reference, "DB_PASSWORD") }
        producer.edit { onAddValue!!() }
        assertNull(producer.editor.literalValue(0))
        // References are only set through the field picker.
        producer.edit { onVariableSourceChange!!(1, reference) }
        assertEquals(EnvironmentValueSource.Literal(""), producer.draft!!.definition.variables[1].source)
        val unsupported = EnvironmentValueSource.Unsupported(kotlinx.serialization.json.JsonNull)
        producer.edit { onVariableSourceChange!!(0, unsupported) }
        assertEquals(reference, producer.draft!!.definition.variables[0].source)
    }

    @Test
    fun verifiedChallengeIsGrantedBeforeLoading() = runTest {
        val service = FakeService().apply { challenge = "session-bound" }
        val producer = producer(service, verifyAccess = { true })
        producer.act { onOpen!!("item") }
        assertTrue(service.loaded)
        assertTrue(service.granted)
        assertNotNull(producer.draft)
    }

    @Test
    fun readOnlyDefinitionsRemainAvailableWithoutAllowingEdits() = runTest {
        val service = FakeService().apply { editable = false }
        val producer = producer(service)
        producer.act { onOpen!!("item") }
        val editor = producer.editor
        assertFalse(editor.draft.editable)
        assertFalse(editor.draft.unsupported)
        assertFalse(editor.draft.canSave)
        assertNull(editor.onNameChange)
        assertNull(editor.onSave)
        assertTrue(editor.draft.definition.variables.isNotEmpty())
    }

    private class FakeService : EnvironmentRepository, EnvironmentAccess {
        override val state = MutableStateFlow(
            EnvironmentCatalog(accounts = listOf(EnvironmentAccount("account", "Account")), unlocked = true),
        )
        override val approvals = MutableStateFlow(emptyList<EnvironmentApprovalRequest>())
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var challenge: String? = null
        var granted = false
        var loaded = false
        var editable = true
        var savedAccountId: String? = null
        var savedDefinition: EnvironmentDefinition? = null
        var savedOwnership: EnvironmentOwnership? = null
        override suspend fun elevationChallenge(id: String): String? = challenge.takeUnless { granted }
        override fun grantElevatedAccess(id: String, challenge: String) {
            check(challenge == this.challenge)
            granted = true
        }
        override suspend fun load(id: String): EnvironmentDocument {
            check(challenge == null || granted)
            loaded = true
            withContext(NonCancellable) { loadGate?.await() }
            val definition = EnvironmentDefinition(
                listOf(EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("secret"))),
            )
            return EnvironmentDocument(id, "account", "Development", "", definition, editable, unsupported = false)
        }
        override suspend fun save(
            id: String?,
            accountId: String,
            name: String,
            definition: EnvironmentDefinition,
            description: String,
            ownership: EnvironmentOwnership?,
        ): String {
            check(id == null || challenge == null || granted)
            saveGate?.await()
            savedAccountId = accountId
            savedDefinition = definition
            savedOwnership = ownership
            return id ?: "created"
        }
        override suspend fun rename(id: String, name: String) = Unit
        override suspend fun trash(id: String) = Unit
        val mounts = object : EnvironmentMounts {
            override val state = MutableStateFlow(EnvironmentMountsState())
            override suspend fun create(environmentId: String, path: String, bookmark: String?) =
                EnvironmentMount("mount", environmentId, path)
            override suspend fun disable(id: String) = Unit
        }
        val mcp = object : EnvironmentMcp {
            override val state = MutableStateFlow(EnvironmentMcpState())
            override suspend fun setEnabled(enabled: Boolean) = Unit
        }
        override fun respondToApproval(id: String, approved: Boolean) = false
        override fun provideDirectoryGrant(path: String, bookmark: String) = Unit
    }

    private companion object {
        val reference = EnvironmentValueSource.Reference(
            cipherId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            field = EnvironmentFieldReference.Password,
        )
    }
}
