package com.artemchep.keyguard.feature.gpgkey.userids

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdInfo
import com.artemchep.keyguard.common.service.crypto.GpgUserIdChange
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationServiceUnsupported
import com.artemchep.keyguard.feature.home.vault.add.DraftGpgKeyMutationCoordinator
import com.artemchep.keyguard.feature.home.vault.add.GpgKeyMutationGuard
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_ids_retry_at
import com.artemchep.keyguard.res.retry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpgUserIdsStateProducerTest {
    @Test
    fun `imported identity text round trips without normalization`() {
        val exact =
            listOf(
                "Alice (work) <alice@example.test>",
                " Alice <alice@example.test>",
                "Alice <alice@example.test> ",
                "Alice  ",
                "Alice <a@example.test> <b@example.test>",
            )
        exact.forEach { value ->
            val form = GpgUserIdForm.parse(value)
            assertTrue(form.exact)
            assertEquals(value, form.userId)
            assertNull(form.toggleFormat())
        }
        listOf("Alice", "Alice <alice@example.test>", "alice@example.test").forEach { value ->
            val form = GpgUserIdForm.parse(value)
            assertFalse(form.exact)
            assertEquals(value, form.userId)
            assertEquals(value, form.toggleFormat()?.toggleFormat()?.userId)
        }
    }

    @Test
    fun `cancel form leaves the draft unchanged and manager stays open`() =
        runTest {
            val fixture = fixture()
            fixture.producer.open()
            fixture
                .state()
                .add
                ?.onClick
                ?.invoke()
            fixture
                .state()
                .fields
                .first { it.id == "name" }
                .value.onChange
                ?.invoke("New name")
            assertNotNull(fixture.state().confirm?.onClick)
            fixture
                .state()
                .back.onClick
                ?.invoke()
            assertEquals(GpgUserIdsState.Page.List, fixture.state().page)
            assertEquals(original, fixture.sink.value)
            assertTrue(fixture.requests.isEmpty())
            fixture
                .state()
                .back.onClick
                ?.invoke()
            assertFalse(fixture.producer.editor.value.open)
        }

    @Test
    fun `make primary targets the stable identity and commits only the draft`() =
        runTest {
            val fixture = fixture()
            fixture.producer.open()
            val primary = fixture.state().identities.first { it.id == "first" }
            assertFalse(primary.actions.any { it.id.endsWith(":primary") })
            fixture
                .state()
                .identities
                .first { it.id == "second" }
                .actions
                .first { it.id.endsWith(":primary") }
                .onClick
                ?.invoke()
            assertEquals(GpgUserIdChange.MakePrimary("second"), fixture.requests.single().change)
            assertEquals("updated", fixture.sink.value.publicKeyArmored)
            assertTrue(fixture.producer.editor.value.open)
        }

    @Test
    fun `duplicate identities cannot be added and stale forms do not mutate newer keys`() =
        runTest {
            val fixture = fixture()
            fixture.producer.open()
            fixture
                .state()
                .add
                ?.onClick
                ?.invoke()
            fixture
                .state()
                .fields
                .first { it.id == "name" }
                .value.onChange
                ?.invoke("First")
            assertNull(fixture.state().confirm?.onClick)
            fixture
                .state()
                .fields
                .first { it.id == "name" }
                .value.onChange
                ?.invoke("New")
            val confirm = fixture.state().confirm?.onClick
            assertNotNull(confirm)
            fixture.guard.replace(original.copy(publicKeyArmored = "newer"))
            confirm()
            assertTrue(fixture.requests.isEmpty())
            assertEquals("newer", fixture.sink.value.publicKeyArmored)
            assertNotNull(fixture.producer.editor.value.error)
        }

    @Test
    fun `public keys and history are view only`() =
        runTest {
            val fixture = fixture()
            fixture.guard.replace(original.copy(privateKeyArmored = ""))
            fixture.producer.open()
            val state = fixture.state()
            assertNull(state.add)
            assertTrue(state.identities.all { it.actions.isEmpty() })
            assertTrue(state.history.all { it.actions.isEmpty() })
            assertEquals("Old", state.history.single().value)
        }

    @Test
    fun `format tabs preserve text and reject lossy or stale selection`() = runTest {
        val fixture = fixture()
        fixture.producer.open()
        fixture.state().add?.onClick?.invoke()
        fixture.state().fields.first { it.id == "name" }.value.onChange?.invoke("Alice")
        fixture.state().fields.first { it.id == "email" }.value.onChange?.invoke("alice@example.test")
        val structured = fixture.state().formats.single { it.selected }
        assertEquals("structured", structured.key)
        val revision = fixture.producer.editor.value.revision
        structured.action.onClick?.invoke()
        assertEquals(revision, fixture.producer.editor.value.revision)

        fixture.state().formats.first { it.key == "exact" }.action.onClick?.invoke()
        assertEquals("exact", fixture.state().formats.single { it.selected }.key)
        assertEquals("Alice <alice@example.test>", fixture.state().fields.single().value.text)
        structured.action.onClick?.invoke()
        assertTrue(fixture.producer.editor.value.form.exact)

        val exact = " Alice (work) <alice@example.test> "
        fixture.state().fields.single().value.onChange?.invoke(exact)
        assertNull(fixture.state().formats.first { it.key == "structured" }.action.onClick)
        assertEquals(exact, fixture.producer.editor.value.form.userId)

        fixture.state().fields.single().value.onChange?.invoke("Alice <alice@example.test>")
        fixture.state().formats.first { it.key == "structured" }.action.onClick?.invoke()
        assertEquals("Alice", fixture.state().fields.first { it.id == "name" }.value.text)
        assertEquals("alice@example.test", fixture.state().fields.first { it.id == "email" }.value.text)
        val busy = fixture.producer.state(fixture.sink.value, info, false, true, fixture.producer.editor.value)!!
        assertTrue(busy.formats.all { it.action.onClick == null })
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `exact input rejects control characters oversized UTF8 and revoked identities`() = runTest {
        val fixture = fixture()
        fixture.producer.open()
        fixture.state().add?.onClick?.invoke()
        fixture.state().formats.first { it.key == "exact" }.action.onClick?.invoke()
        val invalid = listOf(" ", "Name\nInjected", "Name\u2028Injected", "Name\u2029Injected", "😀".repeat(257), "Old")
        for (value in invalid) {
            fixture.state().fields.single().value.onChange?.invoke(value)
            assertNull(fixture.state().confirm?.onClick)
            assertNotNull(fixture.state().error)
        }
        val exact = "  Name (comment) <odd@example.test>  "
        fixture.state().fields.single().value.onChange?.invoke(exact)
        fixture.state().confirm?.onClick?.invoke()
        assertEquals(GpgUserIdChange.Add(exact), fixture.requests.single().change)
    }

    @Test
    fun `primary selection follows its authorization flag`() = runTest {
        val fixture = fixture()
        fixture.producer.open()
        val readOnlyIdentity = info.copy(userIdDetails = info.userIdDetails.map { it.copy(canMakePrimary = false) })
        val state = fixture.producer.state(original, readOnlyIdentity, false, false, fixture.producer.editor.value)!!
        assertTrue(state.identities.all { identity -> identity.actions.none { it.id.endsWith(":primary") } })
    }

    @Test
    fun `revocation follows the native capability flag`() = runTest {
        val requests = mutableListOf<GpgUserIdRevocationRequest>()
        val fixture = fixture(revocationService = recordingRevocationService(requests))
        val reopened = info.copy(userIdDetails = info.userIdDetails.mapIndexed { index, identity ->
            identity.copy(canRevoke = index == 0)
        })
        fixture.producer.open()
        val state = fixture.producer.state(original, reopened, false, false, fixture.producer.editor.value)!!
        assertTrue(state.identities.last().actions.none { it.id.endsWith(":revoke") })
        val revoke = state.identities.first().actions.single { it.id.endsWith(":revoke") }
        assertNotNull(revoke.onClick).invoke()
        assertNotNull(fixture.state().confirm?.onClick).invoke()
        assertEquals("first", requests.single().identityId)
    }

    @Test
    fun `identity actions follow their own flags when adding is unavailable`() = runTest {
        val fixture = fixture(revocationService = recordingRevocationService(mutableListOf()))
        fixture.producer.open()
        val cannotAdd = info.copy(canModifyUserIds = false)
        val state = fixture.producer.state(original, cannotAdd, false, false, fixture.producer.editor.value)!!
        assertNull(state.add)
        assertTrue(state.identities.all { identity -> identity.actions.any { it.id.endsWith(":revoke") } })
    }

    @Test
    fun `clock conflict shows exact retry time and retry preserves the requested change`() = runTest {
        val retryAt = Instant.fromEpochSeconds(160)
        var attempts = 0
        val fixture = fixture(update = {
            if (++attempts == 1) GpgUserIdUpdateResult.Error(GpgUserIdMutationError.TimeConflict, retryAt)
            else GpgUserIdUpdateResult.Success(it.key.copy(publicKeyArmored = "retried"), true)
        })
        fixture.producer.open()
        fixture.state().identities.last().actions.first { it.id.endsWith(":primary") }.onClick?.invoke()
        assertEquals("${Res.string.gpg_user_ids_retry_at}:$retryAt", fixture.state().error)
        assertEquals(Res.string.retry.toString(), fixture.state().confirm?.title)
        assertEquals(original, fixture.sink.value)
        fixture.state().confirm?.onClick?.invoke()
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.all { it.change == GpgUserIdChange.MakePrimary("second") })
        assertEquals("retried", fixture.sink.value.publicKeyArmored)
        assertNull(fixture.state().error)
        assertNull(fixture.state().confirm)
    }

    @Test
    fun `editing after a clock conflict clears the retry and submits the new text`() = runTest {
        val fixture = fixture(update = {
            GpgUserIdUpdateResult.Error(GpgUserIdMutationError.TimeConflict, Instant.fromEpochSeconds(160))
        })
        fixture.producer.open()
        fixture.state().add?.onClick?.invoke()
        fixture.state().fields.first { it.id == "name" }.value.onChange?.invoke("First attempt")
        fixture.state().confirm?.onClick?.invoke()
        val staleRetry = fixture.state().confirm?.onClick
        assertNotNull(staleRetry)
        fixture.state().fields.first { it.id == "name" }.value.onChange?.invoke("Second attempt")
        assertNull(fixture.producer.editor.value.retry)
        staleRetry()
        assertEquals(1, fixture.requests.size)
        fixture.state().confirm?.onClick?.invoke()
        assertEquals(GpgUserIdChange.Add("Second attempt"), fixture.requests.last().change)
    }

    private fun recordingRevocationService(
        requests: MutableList<GpgUserIdRevocationRequest>,
    ) = object : GpgUserIdRevocationService {
        override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult {
            requests += request
            return GpgUserIdRevocationResult.Success(
                key = request.key,
                revocationCertificateArmored = "",
                changed = true,
                effectiveAt = Instant.fromEpochSeconds(100),
            )
        }
    }

    private fun TestScope.fixture(
        revocationService: GpgUserIdRevocationService = GpgUserIdRevocationServiceUnsupported,
        update: (GpgUserIdUpdateRequest) -> GpgUserIdUpdateResult = {
            GpgUserIdUpdateResult.Success(it.key.copy(publicKeyArmored = "updated"), true)
        },
    ): Fixture {
        val sink = MutableStateFlow(original)
        val guard = GpgKeyMutationGuard(sink)
        val requests = mutableListOf<GpgUserIdUpdateRequest>()
        val coordinator =
            DraftGpgKeyMutationCoordinator(
                mutations = guard,
                loadCandidateRevocationKeys = { emptyList() },
                expirationService = GpgKeyExpirationServiceUnsupported,
                userIdReplacementService = GpgUserIdReplacementServiceUnsupported,
                userIdRevocationService = revocationService,
                userIdUpdateService =
                    object : GpgUserIdUpdateService {
                        override fun update(request: GpgUserIdUpdateRequest): GpgUserIdUpdateResult {
                            requests += request
                            return update(request)
                        }
                    },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        val producer =
            GpgUserIdsStateProducer(
                scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                mutations = guard,
                coordinator = coordinator,
                translator = translator,
                formatDateTime = { it.toString() },
            )
        return Fixture(sink, guard, producer, requests)
    }

    private data class Fixture(
        val sink: MutableStateFlow<GeneratedGpgKey>,
        val guard: GpgKeyMutationGuard,
        val producer: GpgUserIdsStateProducer,
        val requests: MutableList<GpgUserIdUpdateRequest>,
    ) {
        suspend fun state() = producer.state(sink.value, info, false, false, producer.editor.value)!!
    }

    private companion object {
        val original = GeneratedGpgKey("secret", "public", "FINGERPRINT", null, "First", "GPG")
        val info =
            GpgPublicKeyInfo(
                fingerprint = "FINGERPRINT",
                keyId = "KEY",
                algorithm = "Ed25519",
                bitStrength = null,
                userIds = listOf("First", "Second"),
                emails = emptyList(),
                createdAt = null,
                expiresAt = null,
                revoked = false,
                canSign = true,
                canEncrypt = true,
                publicKeyArmored = "public",
                subKeys = emptyList(),
                userIdDetails =
                    listOf(
                        GpgUserIdInfo(
                            "first",
                            "First",
                            primary = true,
                            canReplace = true,
                            canRevoke = true,
                            canMakePrimary = true,
                        ),
                        GpgUserIdInfo(
                            "second", "Second", canReplace = true, canRevoke = true, canMakePrimary = true,
                        ),
                    ),
                userIdHistory = listOf(GpgUserIdInfo("old", "Old", revoked = true)),
                canModifyUserIds = true,
            )
        val translator =
            object : TranslatorScope {
                override suspend fun translate(res: StringResource): String = res.toString()

                override suspend fun translate(
                    res: StringResource,
                    vararg args: Any,
                ): String = "${res}:${args.joinToString()}"

                override suspend fun translate(
                    res: PluralStringResource,
                    quantity: Int,
                    vararg args: Any,
                ): String = res.toString()
            }
    }
}
