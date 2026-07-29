package com.artemchep.keyguard.android.credentialexchange

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccount
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountEntry
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRegistration
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialExchangeRegistrationWorkerTest {
    /**
     * The worker runs on the process lifecycle, so it also runs while the vault is
     * locked — including inside the very process the platform starts to answer an
     * export request. Sourcing the targets from the locked-readable mirror is what
     * keeps a locked vault from being read as "no accounts", which used to clear the
     * registration out of the system picker.
     */
    @Test
    fun `a mirrored account is registered with no vault session at all`() = runTest {
        val backend = RecordingRegistrationBackend()
        val registrations = listOf(
            registration(id = "a", entryId = "entry-a", label = "Alpha"),
        )
        val worker = CredentialExchangeRegistrationWorker(
            registry = backend,
            exposedAccountRepository = FakeExposedAccountRepository(
                registrations = MutableStateFlow(registrations),
            ),
            logRepository = RecordingLogRepository(),
        )

        worker.start(backgroundScope, flowOf(LeLifecycleState.STARTED))
        runCurrent()

        assertEquals(
            listOf<BackendCall>(BackendCall.Register(registrations)),
            backend.calls,
        )
    }

    @Test
    fun `emptying the mirror unregisters`() = runTest {
        val backend = RecordingRegistrationBackend()
        val registrations = listOf(
            registration(id = "a", entryId = "entry-a", label = "Alpha"),
        )
        val mirror = MutableStateFlow(registrations)
        val worker = CredentialExchangeRegistrationWorker(
            registry = backend,
            exposedAccountRepository = FakeExposedAccountRepository(mirror),
            logRepository = RecordingLogRepository(),
        )

        worker.start(backgroundScope, flowOf(LeLifecycleState.STARTED))
        runCurrent()
        mirror.value = emptyList()
        runCurrent()

        assertEquals(
            listOf(
                BackendCall.Register(registrations),
                BackendCall.Unregister,
            ),
            backend.calls,
        )
    }

    /**
     * The mirror read now happens on every process start rather than only after an
     * unlock, so a failing read reaches further than it used to. An unreadable mirror
     * is no more evidence that there is nothing to advertise than a locked vault was,
     * so it must leave the registration alone instead of clearing it — and it must not
     * take the lifecycle scope down either.
     */
    @Test
    fun `a failing mirror read leaves the registration standing`() = runTest {
        val backend = RecordingRegistrationBackend()
        val registrations = listOf(
            registration(id = "a", entryId = "entry-a", label = "Alpha"),
        )
        val mirror = MutableStateFlow(registrations)
        val logRepository = RecordingLogRepository()
        val worker = CredentialExchangeRegistrationWorker(
            registry = backend,
            exposedAccountRepository = FakeExposedAccountRepository(
                registrations = mirror.map { snapshot ->
                    if (snapshot.isEmpty()) error("the mirror is unreadable") else snapshot
                },
            ),
            logRepository = logRepository,
        )

        worker.start(backgroundScope, flowOf(LeLifecycleState.STARTED))
        runCurrent()
        // Emptying the flow makes the next read throw rather than report "no accounts".
        mirror.value = emptyList()
        runCurrent()

        // No Unregister: the last good target still stands.
        assertEquals(
            listOf<BackendCall>(BackendCall.Register(registrations)),
            backend.calls,
        )
        assertEquals(listOf(LogLevel.ERROR), logRepository.entries.map { it.level })
    }

    @Test
    fun `only an empty mirror snapshot clears the registration`() {
        val registrations = listOf(
            registration(id = "a", entryId = "entry-a", label = "Alpha"),
        )

        assertEquals(
            CredentialExchangeRegistrationTarget.Ready(registrations),
            credentialExchangeRegistrationTarget(registrations),
        )
        assertEquals(
            CredentialExchangeRegistrationTarget.Cleared,
            credentialExchangeRegistrationTarget(emptyList()),
        )
    }

    @Test
    fun `provider updates are serialized and rapid pending targets are conflated`() = runTest {
        val backend = BlockingRegistrationBackend()
        val applier = CredentialExchangeRegistrationTargetApplier(backend)
        val first = ready(id = "a")
        val second = ready(id = "b")
        val latest = ready(id = "c")
        val pendingTargetsEmitted = CompletableDeferred<Unit>()
        val targets = flow {
            emit(first)
            backend.firstCallStarted.await()
            emit(second)
            emit(latest)
            pendingTargetsEmitted.complete(Unit)
        }

        val job = launch {
            applier.collect(targets)
        }
        backend.firstCallStarted.await()
        pendingTargetsEmitted.await()
        backend.releaseFirstCall.complete(Unit)
        job.join()

        assertEquals(
            listOf<BackendCall>(
                BackendCall.Register(first.registrations),
                BackendCall.Register(latest.registrations),
            ),
            backend.calls,
        )
        assertEquals(1, backend.maxConcurrentCalls)
    }

    @Test
    fun `cancelled collector lets an in-flight provider update finish once`() = runTest {
        val backend = BlockingRegistrationBackend()
        val target = ready(id = "a")
        val applier = CredentialExchangeRegistrationTargetApplier(backend)
        val job = launch {
            applier.collect(flowOf(target))
        }
        backend.firstCallStarted.await()

        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)

        backend.releaseFirstCall.complete(Unit)
        job.join()
        applier.collect(flowOf(target))

        assertEquals(
            listOf<BackendCall>(BackendCall.Register(target.registrations)),
            backend.calls,
        )
    }

    @Test
    fun `failed provider update is retried by the next collection`() = runTest {
        val backend = RecordingRegistrationBackend(
            outcomes = ArrayDeque(listOf(false, true)),
        )
        val target = ready(id = "a")
        val applier = CredentialExchangeRegistrationTargetApplier(backend)

        applier.collect(flowOf(target))
        applier.collect(flowOf(target))

        assertEquals(
            listOf<BackendCall>(
                BackendCall.Register(target.registrations),
                BackendCall.Register(target.registrations),
            ),
            backend.calls,
        )
    }

    private fun ready(
        id: String,
    ) = CredentialExchangeRegistrationTarget.Ready(
        registrations = listOf(
            registration(
                id = id,
                entryId = "entry-$id",
                label = id.uppercase(),
            ),
        ),
    )

    private fun registration(
        id: String,
        entryId: String,
        label: String,
    ) = ExposedAccountRegistration(
        accountId = id,
        entryId = entryId,
        label = label,
    )
}

private sealed interface BackendCall {
    data class Register(
        val registrations: List<ExposedAccountRegistration>,
    ) : BackendCall

    data object Unregister : BackendCall
}

private open class RecordingRegistrationBackend(
    private val outcomes: ArrayDeque<Boolean> = ArrayDeque(),
) : CredentialExchangeRegistrationBackend {
    val calls = mutableListOf<BackendCall>()

    override suspend fun register(
        registrations: List<ExposedAccountRegistration>,
    ): Boolean {
        calls += BackendCall.Register(registrations)
        return outcomes.removeFirstOrNull() ?: true
    }

    override suspend fun unregister(): Boolean {
        calls += BackendCall.Unregister
        return outcomes.removeFirstOrNull() ?: true
    }
}

private class BlockingRegistrationBackend : RecordingRegistrationBackend() {
    val firstCallStarted = CompletableDeferred<Unit>()
    val releaseFirstCall = CompletableDeferred<Unit>()
    var maxConcurrentCalls = 0
        private set

    private var concurrentCalls = 0

    override suspend fun register(
        registrations: List<ExposedAccountRegistration>,
    ): Boolean {
        concurrentCalls += 1
        maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
        return try {
            val result = super.register(registrations)
            if (calls.size == 1) {
                firstCallStarted.complete(Unit)
                releaseFirstCall.await()
            }
            result
        } finally {
            concurrentCalls -= 1
        }
    }
}

/**
 * A mirror that only answers [getRegistrations]; the worker must not need anything
 * else, and above all not a vault session.
 */
private class FakeExposedAccountRepository(
    private val registrations: Flow<List<ExposedAccountRegistration>>,
) : ExposedAccountRepository {
    override fun get(): Flow<List<ExposedAccount>> = emptyFlow()

    override fun getRegistrations(): Flow<List<ExposedAccountRegistration>> = registrations

    override fun resolveEntry(entryId: String): IO<ExposedAccountEntry?> =
        { error("The registration worker never resolves an entry.") }

    override fun replaceAll(
        accounts: List<ExposedAccount>,
        allAccountIds: Set<String>,
    ): IO<Unit> = { error("The registration worker never writes the mirror.") }
}
