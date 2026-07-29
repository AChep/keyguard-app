package com.artemchep.keyguard.feature.credentialexchange.export

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.credentialexchange.CxfAccountResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportService
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfLoginSecret
import com.artemchep.keyguard.common.service.credentialexchange.cxfProfile
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfDocument
import com.artemchep.keyguard.common.service.credentialexchange.FakeSshKeyPkcs8Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A [CxfExportService] whose per-account mapping raises for the profiles named
 * in [failingAccountIds] and delegates everything else to the real one.
 *
 * The real service is total (a mapper failure becomes a counted account skip),
 * so this double is how the *producer's* own backstop — the layer above that
 * boundary — gets exercised at all.
 */
private class PartlyFailingExportService(
    private val failingAccountIds: Set<String>,
    private val delegate: CxfExportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
    ),
) : CxfExportService {
    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult {
        if (profile.accountId in failingAccountIds) {
            error("mapping ${profile.accountId} is broken")
        }
        return delegate.buildAccountResult(profile, ciphers, allowedTypes, folders)
    }

    override fun buildDocument(
        accounts: List<CxfAccount>,
        exporterRpId: String,
        exporterDisplayName: String,
        timestamp: Instant,
    ): CxfDocument = delegate.buildDocument(accounts, exporterRpId, exporterDisplayName, timestamp)

    override fun encode(document: CxfDocument): String = delegate.encode(document)
}

/**
 * The same shape, but *total* — it answers a failing profile with the counted
 * account skip the real service produces, instead of raising. This is what the
 * production path actually looks like once the service boundary is in place.
 */
private class CountingFailureExportService(
    private val failingAccountIds: Set<String>,
    // Overridable so a test can also drive the *uncounted* failure, which the
    // real service never produces but the profile filter still has a branch for.
    private val skips: CxfExportSkips = cxfExportSkips(CxfExportSkipReason.Account to 1),
    private val delegate: CxfExportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
    ),
) : CxfExportService {
    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult {
        if (profile.accountId in failingAccountIds) {
            return CxfAccountResult(
                account = null,
                skips = skips,
            )
        }
        return delegate.buildAccountResult(profile, ciphers, allowedTypes, folders)
    }

    override fun buildDocument(
        accounts: List<CxfAccount>,
        exporterRpId: String,
        exporterDisplayName: String,
        timestamp: Instant,
    ): CxfDocument = delegate.buildDocument(accounts, exporterRpId, exporterDisplayName, timestamp)

    override fun encode(document: CxfDocument): String = delegate.encode(document)
}

/**
 * Raises a [CancellationException] out of the mapping, which the backstop must
 * let through rather than fold into an error step — a cancelled screen has no
 * one left to show an error to.
 */
private class CancellingExportService : CxfExportService {
    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult = throw CancellationException("cancelled")

    override fun buildDocument(
        accounts: List<CxfAccount>,
        exporterRpId: String,
        exporterDisplayName: String,
        timestamp: Instant,
    ): CxfDocument = error("unused")

    override fun encode(document: CxfDocument): String = error("unused")
}

private const val ERROR_MESSAGE = "Something went wrong while preparing the items."
private const val UNAVAILABLE_MESSAGE = "This account is no longer available in Keyguard."

/**
 * The export review screen's failure behaviour, driven through the producer's
 * `internal` seams rather than through Compose: an internal failure must never
 * be reported to the requesting app as user consent withdrawal, and must never
 * leave the screen on a spinner it cannot leave.
 */
class CredentialExchangeExportStateProducerTest {
    private val profileA = cxfProfile(accountId = "acc-1", name = "Alice")
    private val profileB = cxfProfile(accountId = "acc-2", name = "Bob")

    private fun ciphersFor(accountId: String) = listOf(
        cxfLoginSecret(
            id = "login-$accountId",
            accountId = accountId,
            login = DSecret.Login(password = "s3cr3t"),
        ),
    )

    private val allCiphers = ciphersFor("acc-1") + ciphersFor("acc-2")

    private fun profileAccountsWith(service: CxfExportService) = buildProfileAccounts(
        profiles = listOf(profileA, profileB),
        ciphers = allCiphers,
        folders = emptyList(),
        allowedTypes = CxfCredentialType.ALL,
        cxfExportService = service,
    )

    /**
     * Records what the screen told the host, so a test can assert not only what
     * was reported but what was *not*.
     */
    private class CompleteRecorder {
        val results = mutableListOf<CredentialExchangeExportResult>()

        fun complete(result: CredentialExchangeExportResult) {
            results += result
        }
    }

    private fun Step.render(recorder: CompleteRecorder) = toLoadableState(
        exporting = false,
        errorMessage = ERROR_MESSAGE,
        unavailableMessage = UNAVAILABLE_MESSAGE,
        onExporting = {},
        complete = recorder::complete,
    )

    private fun Loadable<CredentialExchangeExportState>.stage() =
        assertIs<Loadable.Ok<CredentialExchangeExportState>>(this).value.stage

    @Test
    fun `one failing account does not take the others down`() {
        // With the service boundary total, the failing profile is answered with
        // a counted skip and the healthy one still exports.
        val accounts = profileAccountsWith(CountingFailureExportService(setOf("acc-1")))
        assertEquals(2, accounts.size)
        val failed = accounts.single { it.profile.accountId == "acc-1" }
        assertNull(failed.result.account)
        assertEquals(1, failed.result.skips[CxfExportSkipReason.Account])
        val healthy = accounts.single { it.profile.accountId == "acc-2" }
        assertEquals(1, healthy.result.account?.items?.size)
    }

    @Test
    fun `a failing account survives the profile filter only because it is counted`() {
        // `buildProfileAccounts` drops a profile with no account and no skips, so
        // if the account reason ever stopped contributing to `totalCount` the
        // failure would vanish from the review entirely.
        val accounts = profileAccountsWith(CountingFailureExportService(setOf("acc-1", "acc-2")))
        assertEquals(
            listOf("acc-1", "acc-2"),
            accounts.map { it.profile.accountId },
        )
        assertTrue(accounts.all { it.result.skips.totalCount > 0 })

        // The drop branch this depends on, exercised: the very same failure with
        // nothing counted takes the profile out of the review altogether.
        val uncounted = profileAccountsWith(
            CountingFailureExportService(
                failingAccountIds = setOf("acc-1", "acc-2"),
                skips = cxfExportSkips(),
            ),
        )
        assertTrue(uncounted.isEmpty())
    }

    @Test
    fun `every account failing is an error, not an empty review`() {
        // An empty list next to a Cancel button would report an internal failure
        // to the requesting app as the user declining.
        val recorder = CompleteRecorder()
        val step = Step.Review(
            listOf(
                profileAccount(profileA, cxfExportSkips(CxfExportSkipReason.Account to 1)),
                profileAccount(profileB, cxfExportSkips(CxfExportSkipReason.Account to 1)),
            ),
        )
        val stage = step.render(recorder).stage()
        assertIs<CredentialExchangeExportState.Stage.Error>(stage)
        // Exactly once, and never as a cancellation the user did not perform.
        assertEquals(1, recorder.results.size)
        assertEquals(CredentialExchangeExportResult.Fail, recorder.results.single())
    }

    @Test
    fun `the backstop turns an escaped throwable into the error step`() {
        // The fold itself, executed: every other case renders a `Step` a test
        // built by hand, so only this one would catch `Step.Error` being replaced
        // with an empty `Step.Review`, which reports an internal failure to the
        // requesting app as the user declining.
        val step = buildStep(
            profiles = listOf(profileA, profileB),
            ciphers = allCiphers,
            folders = emptyList(),
            allowedTypes = CxfCredentialType.ALL,
            cxfExportService = PartlyFailingExportService(setOf("acc-1")),
        )
        assertIs<Step.Error>(step)
    }

    @Test
    fun `the backstop still lets a cancellation through`() {
        assertFailsWith<CancellationException> {
            buildStep(
                profiles = listOf(profileA),
                ciphers = allCiphers,
                folders = emptyList(),
                allowedTypes = CxfCredentialType.ALL,
                cxfExportService = CancellingExportService(),
            )
        }
    }

    @Test
    fun `the backstop passes a healthy vault through as a review`() {
        val step = buildStep(
            profiles = listOf(profileA, profileB),
            ciphers = allCiphers,
            folders = emptyList(),
            allowedTypes = CxfCredentialType.ALL,
            cxfExportService = CxfExportServiceImpl(
                sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
            ),
        )
        assertEquals(2, assertIs<Step.Review>(step).profileAccounts.size)
    }

    @Test
    fun `an escaped throwable becomes the error stage`() {
        val recorder = CompleteRecorder()
        val stage = Step.Error.render(recorder).stage()
        val error = assertIs<CredentialExchangeExportState.Stage.Error>(stage)
        assertEquals(ERROR_MESSAGE, error.message)
        // The stage exposes neither confirm nor deny — the screen has no Cancel
        // button to steer the user into.
        assertEquals(1, recorder.results.size)
        assertEquals(CredentialExchangeExportResult.Fail, recorder.results.single())
    }

    @Test
    fun `an empty but healthy vault is still a review`() {
        // Nothing exportable and nothing skipped is not a failure: the user
        // simply has nothing to transfer, and the existing empty copy applies.
        val recorder = CompleteRecorder()
        val stage = Step.Review(emptyList()).render(recorder).stage()
        val review = assertIs<CredentialExchangeExportState.Stage.Review>(stage)
        assertTrue(review.items.isEmpty())
        assertNull(review.onConfirm)
        assertTrue(recorder.results.isEmpty())
    }

    @Test
    fun `a partly failing vault still offers the transfer`() {
        // One account lost, one intact: the review must render, warn, and let
        // the user send what survived.
        val recorder = CompleteRecorder()
        val accounts = profileAccountsWith(CountingFailureExportService(setOf("acc-1")))
        val stage = Step.Review(accounts).render(recorder).stage()
        val review = assertIs<CredentialExchangeExportState.Stage.Review>(stage)
        // The one item of the account that survived; the lost account contributes
        // no rows, only its warning note.
        assertEquals(1, review.items.size)
        assertEquals(1, review.skipped.size)
        assertTrue(review.onConfirm != null)
        assertTrue(recorder.results.isEmpty())
    }

    @Test
    fun `review items are sorted globally across accounts and reshaped`() {
        val accounts = buildProfileAccounts(
            profiles = listOf(profileA, profileB),
            ciphers = listOf(
                cxfLoginSecret(
                    id = "zulu",
                    accountId = "acc-1",
                    name = "Zulu",
                    login = DSecret.Login(password = "secret"),
                ),
                cxfLoginSecret(
                    id = "middle",
                    accountId = "acc-1",
                    name = "Middle",
                    login = DSecret.Login(password = "secret"),
                ),
                cxfLoginSecret(
                    id = "alpha",
                    accountId = "acc-2",
                    name = "Alpha",
                    login = DSecret.Login(password = "secret"),
                ),
            ),
            folders = emptyList(),
            allowedTypes = CxfCredentialType.ALL,
            cxfExportService = CxfExportServiceImpl(
                sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
            ),
        )

        val review = assertIs<CredentialExchangeExportState.Stage.Review>(
            Step.Review(accounts).render(CompleteRecorder()).stage(),
        )
        assertEquals(listOf("Alpha", "Middle", "Zulu"), review.items.map { it.title })
        assertEquals(
            listOf(ShapeState.START, ShapeState.CENTER, ShapeState.END),
            review.items.map { it.shapeState },
        )
    }

    @Test
    fun `a scoped account keeps only its own profile`() {
        val profiles = listOf(cxfProfile(accountId = "acc-1"), cxfProfile(accountId = "acc-2"))
        val scoped = scopeProfiles(profiles, AccountId("acc-2"))
        assertEquals(listOf("acc-2"), scoped?.map { it.accountId })
    }

    @Test
    fun `an unknown account is unavailable, not an empty review`() {
        // An empty review would read "There are no credentials to transfer", which is
        // the right sentence for a real account that happens to be empty and the
        // wrong one for an account that is gone.
        assertNull(scopeProfiles(listOf(cxfProfile(accountId = "acc-1")), AccountId("acc-9")))
    }

    @Test
    fun `a hidden account is unavailable`() {
        // Explicitly, not as a side effect of the cipher filter starving it of items.
        val profiles = listOf(cxfProfile(accountId = "acc-1").copy(hidden = true))
        assertNull(scopeProfiles(profiles, AccountId("acc-1")))
    }

    @Test
    fun `the unavailable stage reports a cancellation, never a failure`() {
        // The distinction the stage exists for: nothing malfunctioned, so the
        // requesting app must hear the user's own withdrawal rather than an error.
        val recorder = CompleteRecorder()
        val stage = Step.Unavailable.render(recorder).stage()
        val unavailable =
            assertIs<CredentialExchangeExportState.Stage.Unavailable>(stage)
        assertEquals(UNAVAILABLE_MESSAGE, unavailable.message)
        // Nothing is reported merely by rendering it.
        assertTrue(recorder.results.isEmpty())
        unavailable.onClose()
        assertEquals(1, recorder.results.size)
        assertEquals(CredentialExchangeExportResult.Cancel, recorder.results.single())
    }

    @Test
    fun `the un-verified gate renders the verification stage, not a spinner`() {
        // The stage carries the verification form itself, so it can never be a
        // `Loadable.Loading` — a spinner cannot collect a password, and the screen
        // would have no way forward and no way to answer the requesting app.
        val stage = lockedLoadableState(
            onAuthenticated = {},
        ).stage()
        assertIs<CredentialExchangeExportState.Stage.Locked>(stage)
        // The type-level half of the guarantee: the gate is not something the review
        // scaffold can render, so it cannot reach the toolbar/FAB surface at all.
        assertFalse(stage is CredentialExchangeExportState.Stage.Reviewable)
    }

    @Test
    fun `sitting at the gate and passing it both report nothing to the host`() {
        // The gate has no deny of its own: it is rendered in place rather than as a
        // dialog, so the host's own consent header answers the requesting app. What
        // must hold is that the gate itself never speaks for the user.
        val recorder = CompleteRecorder()
        var opened = 0
        val stage = lockedLoadableState(
            onAuthenticated = { opened++ },
        ).stage()
        val locked = assertIs<CredentialExchangeExportState.Stage.Locked>(stage)
        assertTrue(recorder.results.isEmpty())
        locked.onAuthenticated()
        assertEquals(1, opened)
        assertTrue(recorder.results.isEmpty())
    }

    @Test
    fun `a passed gate shows progress before the mapping produces a review`() = runTest {
        // The regression this pins: the review flow cannot emit until every vault
        // source has and the mapping has decoded each passkey, so a screen that only
        // renders what the flow gives it kept showing the retained locked stage —
        // "nothing has been shared yet" next to a button that no longer does anything.
        val recorder = CompleteRecorder()
        val neverEmits = flow<Loadable<CredentialExchangeExportState>> {
            awaitCancellation()
        }
        val stage = neverEmits
            .startWithMapping(complete = recorder::complete)
            .first()
            .stage()
        assertIs<CredentialExchangeExportState.Stage.Mapping>(stage)
        // Rendering progress reports nothing to the host.
        assertTrue(recorder.results.isEmpty())
    }

    @Test
    fun `the mapping stage can still decline`() {
        // The one stage that is still working, so it is the one that would strand the
        // requesting app if it had no way out.
        val recorder = CompleteRecorder()
        val stage = mappingLoadableState(complete = recorder::complete).stage()
        val mapping = assertIs<CredentialExchangeExportState.Stage.Mapping>(stage)
        mapping.onDeny()
        assertEquals(1, recorder.results.size)
        assertEquals(CredentialExchangeExportResult.Cancel, recorder.results.single())
    }
}

private fun profileAccount(
    profile: DProfile,
    skips: CxfExportSkips,
) = ProfileAccount(
    profile = profile,
    result = CxfAccountResult(account = null, skips = skips),
)
