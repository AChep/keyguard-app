package com.artemchep.keyguard.feature.android.ipc.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidIpcApprovalStateProducerTest {
    @Test
    fun `state stays loading until the request snapshot is available`() = runTest {
        val snapshot = CompletableDeferred<AndroidIpcApprovalSnapshot?>()
        val producer = fixture(loadSnapshot = { snapshot.await() })

        val loadJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            producer.load()
        }

        assertEquals(AndroidIpcApprovalPresentationState.Loading, producer.state.value)
        snapshot.complete(request())
        loadJob.join()
        assertIs<AndroidIpcApprovalPresentationState.Ready>(producer.state.value)
    }

    @Test
    fun `missing request becomes unavailable`() = runTest {
        val producer = fixture(loadSnapshot = { null })

        producer.load()

        assertEquals(AndroidIpcApprovalPresentationState.Unavailable, producer.state.value)
    }

    @Test
    fun `producer loads the requested id and exposes the snapshot`() = runTest {
        val loadedIds = mutableListOf<String>()
        val snapshot = request(id = "snapshot")
        val producer = fixture(
            requestId = "requested",
            loadSnapshot = { id ->
                loadedIds += id
                snapshot
            },
        )

        producer.load()

        assertEquals(listOf("requested"), loadedIds)
        assertEquals(snapshot, producer.readyState().request)
    }

    @Test
    fun `exactly one preselected candidate is selected initially`() = runTest {
        val producer = fixture(
            snapshot = request(
                candidates = listOf(
                    candidate("first"),
                    candidate("second", preselected = true),
                ),
            ),
        )

        producer.load()

        assertEquals(setOf("second"), producer.readyState().selectedKeyIds)
    }

    @Test
    fun `ambiguous preselection does not select a candidate`() = runTest {
        val producer = fixture(
            snapshot = request(
                candidates = listOf(
                    candidate("first", preselected = true),
                    candidate("second", preselected = true),
                ),
            ),
        )

        producer.load()

        assertEquals(emptySet(), producer.readyState().selectedKeyIds)
    }

    @Test
    fun `a sole candidate is selected without a preselection hint`() = runTest {
        val producer = fixture(
            snapshot = request(candidates = listOf(candidate("only"))),
        )

        producer.load()

        assertEquals(setOf("only"), producer.readyState().selectedKeyIds)
    }

    @Test
    fun `multiple selection toggles candidates independently`() = runTest {
        val producer = fixture(
            snapshot = request(
                candidates = listOf(candidate("first"), candidate("second")),
                allowMultiple = true,
            ),
        )
        producer.load()

        producer.readyState().onSelect("first")
        producer.readyState().onSelect("second")
        producer.readyState().onSelect("first")

        assertEquals(setOf("second"), producer.readyState().selectedKeyIds)
    }

    @Test
    fun `single selection replaces the selected candidate`() = runTest {
        val producer = fixture(
            snapshot = request(
                candidates = listOf(candidate("first"), candidate("second")),
                allowMultiple = false,
            ),
        )
        producer.load()

        producer.readyState().onSelect("first")
        producer.readyState().onSelect("second")

        assertEquals(setOf("second"), producer.readyState().selectedKeyIds)
    }

    @Test
    fun `approval uses the latest non-empty selection`() = runTest {
        val approvals = mutableListOf<Set<String>>()
        val producer = fixture(
            snapshot = request(
                candidates = listOf(candidate("first"), candidate("second")),
                allowMultiple = true,
            ),
            onApprove = approvals::add,
        )
        producer.load()
        val firstApprove = producer.readyState().onApprove

        producer.readyState().onSelect("second")
        firstApprove()

        assertEquals(listOf(setOf("second")), approvals)
    }

    @Test
    fun `empty approval is gated by allow empty`() = runTest {
        val approvals = mutableListOf<Set<String>>()
        val deniedProducer = fixture(
            snapshot = request(allowEmpty = false),
            onApprove = approvals::add,
        )
        deniedProducer.load()
        deniedProducer.readyState().onApprove()

        val allowedProducer = fixture(
            snapshot = request(allowEmpty = true),
            onApprove = approvals::add,
        )
        allowedProducer.load()
        allowedProducer.readyState().onApprove()

        assertEquals(listOf(emptySet()), approvals)
    }

    @Test
    fun `deny delegates to the host callback`() = runTest {
        var denyCount = 0
        val producer = fixture(
            snapshot = request(),
            onDeny = { denyCount += 1 },
        )
        producer.load()

        producer.readyState().onDeny()

        assertEquals(1, denyCount)
    }

    private fun fixture(
        requestId: String = "request",
        snapshot: AndroidIpcApprovalSnapshot = request(),
        loadSnapshot: suspend (String) -> AndroidIpcApprovalSnapshot? = { snapshot },
        onApprove: (Set<String>) -> Unit = {},
        onDeny: () -> Unit = {},
    ) = AndroidIpcApprovalStateProducer(
        requestId = requestId,
        loadSnapshot = loadSnapshot,
        onApprove = onApprove,
        onDeny = onDeny,
    )

    private fun AndroidIpcApprovalStateProducer.readyState() =
        assertIs<AndroidIpcApprovalPresentationState.Ready>(state.value)

    private companion object {
        fun candidate(
            id: String,
            preselected: Boolean = false,
        ) = AndroidIpcApprovalCandidate(
            id = id,
            name = id,
            description = "description",
            preselected = preselected,
        )

        fun request(
            id: String = "request",
            candidates: List<AndroidIpcApprovalCandidate> = emptyList(),
            allowMultiple: Boolean = false,
            allowEmpty: Boolean = false,
        ) = AndroidIpcApprovalSnapshot(
            id = id,
            appLabel = "App",
            packageName = "com.example.app",
            candidates = candidates,
            allowMultiple = allowMultiple,
            allowEmpty = allowEmpty,
            registerApp = false,
            requiresAuthentication = false,
        )
    }
}
