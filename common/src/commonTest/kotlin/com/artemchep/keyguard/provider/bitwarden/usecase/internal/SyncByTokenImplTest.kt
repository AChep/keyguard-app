package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncByTokenImplTest {
    @Test
    fun `same account keeps one active sync and one pending sync`() = runTest {
        val delegate = ControlledSync()
        val sync = syncByToken(delegate, this)
        val activeJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "active")).bind()
        }
        runCurrent()
        val active = delegate.started.receive()

        val pendingJobs = (1..9).map { index ->
            async {
                sync(bitwardenToken(id = "account-1", accessToken = "pending-$index")).bind()
            }.also {
                runCurrent()
            }
        }

        assertTrue(delegate.started.tryReceive().isFailure)

        active.result.complete(Unit)
        runCurrent()
        val pending = delegate.started.receive()

        assertEquals("pending-9", (pending.token as BitwardenToken).token?.accessToken)
        pending.result.complete(Unit)

        listOf(activeJob, *pendingJobs.toTypedArray()).awaitAll()
        runCurrent()

        assertEquals(2, delegate.invocationCount)
        assertTrue(delegate.started.tryReceive().isFailure)
    }

    @Test
    fun `extra same account triggers join pending failure`() = runTest {
        val delegate = ControlledSync()
        val sync = syncByToken(delegate, this)
        val activeJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "active")).bind()
        }
        runCurrent()
        val active = delegate.started.receive()

        val pendingJobs = (1..3).map { index ->
            async {
                runCatching {
                    sync(bitwardenToken(id = "account-1", accessToken = "pending-$index")).bind()
                }
            }.also {
                runCurrent()
            }
        }
        val failure = IllegalStateException("pending failed")

        active.result.complete(Unit)
        runCurrent()
        val pending = delegate.started.receive()
        pending.result.completeExceptionally(failure)

        activeJob.await()
        val pendingResults = pendingJobs.awaitAll()

        assertEquals(2, delegate.invocationCount)
        pendingResults.forEach { result ->
            assertTrue(result.isFailure)
            val error = assertIs<IllegalStateException>(result.exceptionOrNull())
            assertEquals(failure.message, error.message)
        }
    }

    @Test
    fun `pending sync still executes after active sync fails`() = runTest {
        val delegate = ControlledSync()
        val sync = syncByToken(delegate, this)
        val activeFailure = IllegalStateException("active failed")
        val activeJob = async {
            runCatching {
                sync(bitwardenToken(id = "account-1", accessToken = "active")).bind()
            }
        }
        runCurrent()
        val active = delegate.started.receive()
        val pendingJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "pending")).bind()
        }
        runCurrent()

        active.result.completeExceptionally(activeFailure)
        runCurrent()
        val pending = delegate.started.receive()
        pending.result.complete(Unit)

        val activeResult = activeJob.await()
        assertTrue(activeResult.isFailure)
        val error = assertIs<IllegalStateException>(activeResult.exceptionOrNull())
        assertEquals(activeFailure.message, error.message)
        pendingJob.await()
        assertEquals(2, delegate.invocationCount)
    }

    @Test
    fun `different accounts can sync concurrently`() = runTest {
        val delegate = ControlledSync()
        val sync = syncByToken(delegate, this)
        val bitwardenJob = async {
            sync(bitwardenToken(id = "bitwarden-account")).bind()
        }
        runCurrent()
        val bitwarden = delegate.started.receive()

        val keePassJob = async {
            sync(keePassToken(id = "keepass-account")).bind()
        }
        runCurrent()
        val keePass = delegate.started.receive()

        assertEquals("bitwarden-account", bitwarden.token.id)
        assertEquals("keepass-account", keePass.token.id)

        bitwarden.result.complete(Unit)
        keePass.result.complete(Unit)
        bitwardenJob.await()
        keePassJob.await()
        assertEquals(2, delegate.invocationCount)
    }

    @Test
    fun `cancelled pending waiter does not cancel admitted sync`() = runTest {
        val delegate = ControlledSync()
        val sync = syncByToken(delegate, this)
        val activeJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "active")).bind()
        }
        runCurrent()
        val active = delegate.started.receive()

        val cancelledPendingJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "pending-1")).bind()
        }
        runCurrent()
        val joinedPendingJob = async {
            sync(bitwardenToken(id = "account-1", accessToken = "pending-2")).bind()
        }
        runCurrent()

        cancelledPendingJob.cancelAndJoin()

        active.result.complete(Unit)
        runCurrent()
        val pending = delegate.started.receive()

        assertEquals("pending-2", (pending.token as BitwardenToken).token?.accessToken)
        pending.result.complete(Unit)

        activeJob.await()
        joinedPendingJob.await()
        assertEquals(2, delegate.invocationCount)
    }

    private fun syncByToken(
        delegate: ControlledSync,
        syncScope: CoroutineScope,
    ) = SyncByTokenImpl(
        syncByBitwardenToken =
            object : SyncByBitwardenToken {
                override fun invoke(user: BitwardenToken): IO<Unit> = delegate.sync(user)
            },
        syncByKeePassToken =
            object : SyncByKeePassToken {
                override fun invoke(user: KeePassToken): IO<Unit> = delegate.sync(user)
            },
        syncScope = syncScope,
    )

    private class ControlledSync {
        val started = Channel<Invocation>(capacity = Channel.UNLIMITED)
        private val invocations = mutableListOf<Invocation>()

        val invocationCount: Int
            get() = invocations.size

        fun sync(token: ServiceToken): IO<Unit> = {
            val invocation = Invocation(token = token)
            invocations += invocation
            started.send(invocation)
            invocation.result.await()
        }
    }

    private data class Invocation(
        val token: ServiceToken,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )
}

private fun bitwardenToken(
    id: String,
    accessToken: String = id,
) = BitwardenToken(
    id = id,
    key =
        BitwardenToken.Key(
            masterKeyBase64 = "",
            passwordKeyBase64 = "",
            encryptionKeyBase64 = "",
            macKeyBase64 = "",
        ),
    token =
        BitwardenToken.Token(
            refreshToken = "refresh-$accessToken",
            accessToken = accessToken,
            expirationDate = Instant.parse("2099-01-01T00:00:00Z"),
        ),
    user = BitwardenToken.User(email = "$id@example.com"),
    env = BitwardenToken.Environment(),
)

private fun keePassToken(
    id: String,
) = KeePassToken(
    id = id,
    key =
        KeePassToken.Key(
            passwordBase64 = "password",
        ),
    files =
        KeePassToken.Files(
            databaseUri = "file:///$id.kdbx",
            databaseFileName = "$id.kdbx",
        ),
)
