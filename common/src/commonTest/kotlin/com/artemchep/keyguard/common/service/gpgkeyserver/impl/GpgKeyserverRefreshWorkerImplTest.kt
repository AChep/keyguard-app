package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.RefreshGpgPublicKeys
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GpgKeyserverRefreshWorkerImplTest {
    private val interval = 7.days

    @Test
    fun `disabled auto-refresh never triggers a refresh`() = runTest {
        val now = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val lastRefresh = MutableStateFlow<Instant?>(null)
        val refresh = RecordingRefreshGpgPublicKeys(
            lastRefresh = lastRefresh,
            now = now,
        )
        val worker = createWorker(
            autoRefresh = MutableStateFlow(false),
            interval = MutableStateFlow(interval),
            lastRefresh = lastRefresh,
            ciphers = MutableStateFlow(
                listOf(
                    gpgSecret(id = "eligible"),
                    loginSecret(id = "login"),
                ),
            ),
            refresh = refresh,
            now = now,
        )

        val job = worker.launch(this)
        try {
            advanceUntilIdle()

            assertEquals(emptyList(), refresh.requests)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `enabled auto-refresh without a timestamp refreshes eligible ciphers once`() = runTest {
        val now = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val lastRefresh = MutableStateFlow<Instant?>(null)
        val refresh = RecordingRefreshGpgPublicKeys(
            lastRefresh = lastRefresh,
            now = now,
        )
        val worker = createWorker(
            autoRefresh = MutableStateFlow(true),
            interval = MutableStateFlow(interval),
            lastRefresh = lastRefresh,
            ciphers = MutableStateFlow(
                listOf(
                    gpgSecret(id = "eligible"),
                    loginSecret(id = "login"),
                ),
            ),
            refresh = refresh,
            now = now,
        )

        val job = worker.launch(this)
        try {
            // The refresh is immediately due (no last-refresh timestamp).
            runCurrent()
            assertEquals(1, refresh.requests.size)
            assertEquals(setOf("eligible"), refresh.requests.single().cipherIds)

            // No further refresh should happen before the interval elapses.
            advanceTimeBy(interval - 1.days)
            runCurrent()
            assertEquals(1, refresh.requests.size)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `enabled auto-refresh with a fresh timestamp waits for the remaining interval`() = runTest {
        val now = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val baseline = now()
        val lastRefresh = MutableStateFlow<Instant?>(baseline)
        val refresh = RecordingRefreshGpgPublicKeys(
            lastRefresh = lastRefresh,
            now = now,
        )
        val worker = createWorker(
            autoRefresh = MutableStateFlow(true),
            interval = MutableStateFlow(interval),
            lastRefresh = lastRefresh,
            ciphers = MutableStateFlow(
                listOf(
                    gpgSecret(id = "eligible"),
                ),
            ),
            refresh = refresh,
            now = now,
        )

        val job = worker.launch(this)
        try {
            // Not yet due - the key was refreshed at the baseline.
            runCurrent()
            assertEquals(emptyList(), refresh.requests)

            advanceTimeBy(interval - 1.days)
            runCurrent()
            assertEquals(emptyList(), refresh.requests)

            // The remaining time elapses, so the refresh fires.
            advanceTimeBy(1.days)
            runCurrent()
            assertEquals(1, refresh.requests.size)
            assertEquals(setOf("eligible"), refresh.requests.single().cipherIds)
        } finally {
            job.cancel()
        }
    }

    private fun createWorker(
        autoRefresh: Flow<Boolean>,
        interval: Flow<Duration>,
        lastRefresh: Flow<Instant?>,
        ciphers: Flow<List<DSecret>>,
        refresh: RefreshGpgPublicKeys,
        now: () -> Instant,
    ) = GpgKeyserverRefreshWorkerImpl(
        getGpgKeyserverAutoRefresh = object : GetGpgKeyserverAutoRefresh {
            override fun invoke(): Flow<Boolean> = autoRefresh
        },
        getGpgKeyserverRefreshInterval = object : GetGpgKeyserverRefreshInterval {
            override fun invoke(): Flow<Duration> = interval
        },
        getGpgKeyserverLastRefresh = object : GetGpgKeyserverLastRefresh {
            override fun invoke(): Flow<Instant?> = lastRefresh
        },
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = ciphers
        },
        refreshGpgPublicKeys = refresh,
        logRepository = NoOpLogRepository,
        now = now,
    )

    private class RecordingRefreshGpgPublicKeys(
        private val lastRefresh: MutableStateFlow<Instant?>,
        private val now: () -> Instant,
    ) : RefreshGpgPublicKeys {
        val requests = mutableListOf<RefreshGpgPublicKeysRequest>()

        override fun invoke(
            request: RefreshGpgPublicKeysRequest,
        ): IO<RefreshGpgPublicKeysResult> = ioEffect {
            requests += request
            // Mimic the real use-case which writes the last-refresh timestamp
            // upon completion.
            lastRefresh.value = now()
            RefreshGpgPublicKeysResult(
                refreshed = request.cipherIds.size,
                notFound = 0,
                skipped = 0,
            )
        }
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }

    private fun gpgSecret(
        id: String,
        fingerprint: String = primaryFingerprint,
    ): DSecret = createSecret(
        id = id,
        type = DSecret.Type.GpgKey,
        gpgKey = DSecret.GpgKey(
            privateKeyArmored = null,
            publicKeyArmored = "public",
            fingerprint = fingerprint,
            metadata = null,
        ),
    )

    private fun loginSecret(
        id: String,
    ): DSecret = createSecret(
        id = id,
        type = DSecret.Type.Login,
        gpgKey = null,
    )

    private fun createSecret(
        id: String,
        type: DSecret.Type,
        gpgKey: DSecret.GpgKey?,
    ): DSecret = DSecret(
        id = id,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.fromEpochSeconds(0),
        createdDate = null,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = "GPG key",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = type,
        gpgKey = gpgKey,
    )

    private companion object {
        private const val primaryFingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
    }
}
