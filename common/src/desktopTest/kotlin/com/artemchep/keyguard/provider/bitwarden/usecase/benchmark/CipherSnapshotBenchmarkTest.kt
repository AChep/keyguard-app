package com.artemchep.keyguard.provider.bitwarden.usecase.benchmark

import com.artemchep.keyguard.common.usecase.CipherSnapshot
import com.artemchep.keyguard.common.usecase.CipherSnapshotKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherSnapshotLoadResult
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherSnapshotLoadStats
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherSnapshotLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource

class CipherSnapshotBenchmarkTest {
    @Test
    fun `initial snapshot load benchmark`() = runBlocking {
        val state = createState()

        val result = benchmark("initial-load") {
            state.loader.load(
                db = state.db,
                previousSnapshotsByCipherId = emptyMap(),
            )
        }

        assertEquals(CIPHER_COUNT, result.stats.loadedPayloadCount)
        assertTrue(result.stats.isFullLoad)
    }

    @Test
    fun `initial load comparison with pre snapshot query for ten thousand ciphers`() = runBlocking {
        val currentState = createState(INITIAL_LOAD_COMPARISON_CIPHER_COUNT)
        val oldState = createState(INITIAL_LOAD_COMPARISON_CIPHER_COUNT)
        val currentLoad: suspend () -> CipherSnapshotLoadResult = {
            currentState.loader.load(
                db = currentState.db,
                previousSnapshotsByCipherId = emptyMap(),
            )
        }
        val oldLoad: suspend () -> CipherSnapshotLoadResult = {
            loadUsingPreSnapshotQuery(
                db = oldState.db,
                previousSnapshotsByCipherId = emptyMap(),
            )
        }

        repeat(COMPARISON_WARMUP_ITERATIONS) { iteration ->
            if (iteration % 2 == 0) {
                currentLoad()
                oldLoad()
            } else {
                oldLoad()
                currentLoad()
            }
        }

        val currentSamplesNs = mutableListOf<Long>()
        val oldSamplesNs = mutableListOf<Long>()
        var currentResult: CipherSnapshotLoadResult? = null
        var oldResult: CipherSnapshotLoadResult? = null
        repeat(COMPARISON_MEASUREMENT_ITERATIONS) { iteration ->
            if (iteration % 2 == 0) {
                measure(currentLoad).also { measurement ->
                    currentResult = measurement.result
                    currentSamplesNs += measurement.elapsedNs
                }
                measure(oldLoad).also { measurement ->
                    oldResult = measurement.result
                    oldSamplesNs += measurement.elapsedNs
                }
            } else {
                measure(oldLoad).also { measurement ->
                    oldResult = measurement.result
                    oldSamplesNs += measurement.elapsedNs
                }
                measure(currentLoad).also { measurement ->
                    currentResult = measurement.result
                    currentSamplesNs += measurement.elapsedNs
                }
            }
        }
        val currentMedianNs = printBenchmarkSamples(
            caseName = "comparison/current-initial-load",
            cipherCount = INITIAL_LOAD_COMPARISON_CIPHER_COUNT,
            samplesNs = currentSamplesNs,
        )
        val oldMedianNs = printBenchmarkSamples(
            caseName = "comparison/old-initial-load",
            cipherCount = INITIAL_LOAD_COMPARISON_CIPHER_COUNT,
            samplesNs = oldSamplesNs,
        )
        assertEquals(
            INITIAL_LOAD_COMPARISON_CIPHER_COUNT,
            requireNotNull(currentResult).stats.loadedPayloadCount,
        )
        assertEquals(
            INITIAL_LOAD_COMPARISON_CIPHER_COUNT,
            requireNotNull(oldResult).stats.loadedPayloadCount,
        )
        println(
            "cipher-snapshot/comparison initial-load " +
                "currentToOldRatio=${formatRatio(currentMedianNs.toDouble() / oldMedianNs)}x",
        )
    }

    @Test
    fun `single cipher snapshot update benchmark`() = runBlocking {
        val state = createState()
        var previous = state.initialLoad()
        var iteration = 0

        val result = benchmark(
            caseName = "single-change",
            beforeEach = {
                iteration += 1
                state.db.insert(
                    cipher = state.ciphers.first().copy(name = "Changed $iteration"),
                    updatedAt = T1,
                )
            },
        ) {
            state.loader.load(
                db = state.db,
                previousSnapshotsByCipherId = previous,
            ).also { load ->
                previous = load.snapshotsByCipherId
            }
        }

        assertEquals(1, result.stats.loadedPayloadCount)
        assertFalse(result.stats.isFullLoad)
    }

    @Test
    fun `bulk cipher snapshot update benchmark`() = runBlocking {
        val state = createState()
        var previous = state.initialLoad()
        var iteration = 0

        val result = benchmark(
            caseName = "bulk-change",
            beforeEach = {
                iteration += 1
                state.ciphers.take(BULK_CHANGE_COUNT).forEach { cipher ->
                    state.db.insert(
                        cipher = cipher.copy(name = "${cipher.name} $iteration"),
                        updatedAt = T1,
                    )
                }
            },
        ) {
            state.loader.load(
                db = state.db,
                previousSnapshotsByCipherId = previous,
            ).also { load ->
                previous = load.snapshotsByCipherId
            }
        }

        assertEquals(CIPHER_COUNT, result.stats.loadedPayloadCount)
        assertTrue(result.stats.isFullLoad)
    }

    @Test
    fun `single change comparison with old full payload loader`() = runBlocking {
        val keyFirstState = createState()
        var keyFirstPrevious = keyFirstState.initialLoad()
        var keyFirstIteration = 0
        var keyFirstMedianNs = 0L
        val keyFirstResult = benchmark(
            caseName = "comparison/key-first-single-change",
            beforeEach = {
                keyFirstIteration += 1
                keyFirstState.db.insert(
                    cipher = keyFirstState.ciphers.first().copy(
                        name = "Key first $keyFirstIteration",
                    ),
                    updatedAt = T1,
                )
            },
            onComplete = { medianNs -> keyFirstMedianNs = medianNs },
        ) {
            keyFirstState.loader.load(
                db = keyFirstState.db,
                previousSnapshotsByCipherId = keyFirstPrevious,
            ).also { load ->
                keyFirstPrevious = load.snapshotsByCipherId
            }
        }

        val oldState = createState()
        var oldPrevious = loadUsingPreSnapshotQuery(
            db = oldState.db,
            previousSnapshotsByCipherId = emptyMap(),
        ).snapshotsByCipherId
        var oldIteration = 0
        var oldMedianNs = 0L
        val oldResult = benchmark(
            caseName = "comparison/old-full-payload-single-change",
            beforeEach = {
                oldIteration += 1
                oldState.db.insert(
                    cipher = oldState.ciphers.first().copy(
                        name = "Old loader $oldIteration",
                    ),
                    updatedAt = T1,
                )
            },
            onComplete = { medianNs -> oldMedianNs = medianNs },
        ) {
            loadUsingPreSnapshotQuery(
                db = oldState.db,
                previousSnapshotsByCipherId = oldPrevious,
            ).also { load ->
                oldPrevious = load.snapshotsByCipherId
            }
        }

        assertEquals(1, keyFirstResult.stats.loadedPayloadCount)
        assertEquals(1, keyFirstResult.stats.changedCipherCount)
        assertEquals(CIPHER_COUNT, oldResult.stats.loadedPayloadCount)
        assertEquals(1, oldResult.stats.changedCipherCount)
        println(
            "cipher-snapshot/comparison single-change " +
                "speedup=${formatRatio(oldMedianNs.toDouble() / keyFirstMedianNs)}x",
        )
    }

    private suspend fun benchmark(
        caseName: String,
        cipherCount: Int = CIPHER_COUNT,
        beforeEach: () -> Unit = {},
        onComplete: (medianNs: Long) -> Unit = {},
        block: suspend () -> CipherSnapshotLoadResult,
    ): CipherSnapshotLoadResult {
        val samplesNs = mutableListOf<Long>()
        var result: CipherSnapshotLoadResult? = null
        repeat(WARMUP_ITERATIONS + MEASUREMENT_ITERATIONS) { iteration ->
            beforeEach()
            val measurement = measure(block)
            result = measurement.result
            if (iteration >= WARMUP_ITERATIONS) {
                samplesNs += measurement.elapsedNs
            }
        }
        val medianNs = printBenchmarkSamples(
            caseName = caseName,
            cipherCount = cipherCount,
            samplesNs = samplesNs,
        )
        onComplete(medianNs)
        return requireNotNull(result)
    }

    private suspend fun measure(
        block: suspend () -> CipherSnapshotLoadResult,
    ): LoadMeasurement {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        return LoadMeasurement(
            result = result,
            elapsedNs = mark.elapsedNow().inWholeNanoseconds,
        )
    }

    private fun printBenchmarkSamples(
        caseName: String,
        cipherCount: Int,
        samplesNs: List<Long>,
    ): Long {
        val sortedSamples = samplesNs.sorted()
        val medianNs = sortedSamples[sortedSamples.size / 2]
        val p95Index = ceil(sortedSamples.lastIndex * 0.95).toInt()
        println(
            "cipher-snapshot/$caseName ciphers=$cipherCount " +
                "medianMs=${formatMilliseconds(medianNs)} " +
                "p95Ms=${formatMilliseconds(sortedSamples[p95Index])}",
        )
        return medianNs
    }

    /**
     * Uses the full-row query from before the snapshot loader. After the database read it builds
     * current snapshots so the benchmark keeps the non-SQL work identical between both sides.
     */
    private suspend fun loadUsingPreSnapshotQuery(
        db: Database,
        previousSnapshotsByCipherId: Map<String, CipherSnapshot>,
    ): CipherSnapshotLoadResult {
        val rows = withContext(Dispatchers.IO) {
            db.cipherQueries
                .get()
                .executeAsList()
        }
        var changedCipherCount = 0
        val nextSnapshotsByCipherId = HashMap<String, CipherSnapshot>(rows.size)
        val snapshots = rows.map { row ->
            val key = CipherSnapshotKey(
                cipherId = row.cipherId,
                dataRevCounter = row.dataRevCounter,
            )
            val snapshot = previousSnapshotsByCipherId[row.cipherId]
                ?.takeIf { previous -> previous.key == key }
                ?: run {
                    changedCipherCount += 1
                    CipherSnapshot(
                        cipher = row.data_.toDomain(UploadTestPasswordStrength),
                        key = key,
                    )
                }
            nextSnapshotsByCipherId[row.cipherId] = snapshot
            snapshot
        }
        return CipherSnapshotLoadResult(
            snapshots = snapshots,
            snapshotsByCipherId = nextSnapshotsByCipherId,
            stats = CipherSnapshotLoadStats(
                cipherCount = rows.size,
                changedCipherCount = changedCipherCount,
                loadedPayloadCount = rows.size,
                isFullLoad = true,
            ),
        )
    }

    private fun createState(cipherCount: Int = CIPHER_COUNT): BenchmarkState {
        val db = createUploadTestDatabase()
        val ciphers = (1..cipherCount).map(::loginCipher)
        ciphers.forEach { cipher -> db.insert(cipher) }
        return BenchmarkState(
            db = db,
            ciphers = ciphers,
            loader = CipherSnapshotLoader(
                dbDispatcher = Dispatchers.IO,
                getPasswordStrength = UploadTestPasswordStrength,
            ),
        )
    }

    private fun loginCipher(index: Int) = testCipher(
        localId = "cipher-$index",
        remoteId = "remote-cipher-$index",
        localRevisionDate = T0,
        remoteRevisionDate = T0,
        attachments = emptyList(),
    ).copy(
        type = BitwardenCipher.Type.Login,
        secureNote = null,
        login = BitwardenCipher.Login(
            password = "password-$index",
            uris = emptyList(),
        ),
    )

    private fun Database.insert(
        cipher: BitwardenCipher,
        updatedAt: Instant = T0,
    ) {
        cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = updatedAt,
        )
    }

    private fun formatMilliseconds(nanoseconds: Long): String =
        ((nanoseconds / 10_000L) / 100.0).toString()

    private fun formatRatio(ratio: Double): String =
        ((ratio * 100.0).toLong() / 100.0).toString()

    private data class BenchmarkState(
        val db: Database,
        val ciphers: List<BitwardenCipher>,
        val loader: CipherSnapshotLoader,
    ) {
        suspend fun initialLoad(): Map<String, CipherSnapshot> = loader.load(
            db = db,
            previousSnapshotsByCipherId = emptyMap(),
        ).snapshotsByCipherId
    }

    private data class LoadMeasurement(
        val result: CipherSnapshotLoadResult,
        val elapsedNs: Long,
    )

    private companion object {
        const val CIPHER_COUNT = 1_000
        const val INITIAL_LOAD_COMPARISON_CIPHER_COUNT = 10_000
        const val BULK_CHANGE_COUNT = 300
        const val WARMUP_ITERATIONS = 2
        const val MEASUREMENT_ITERATIONS = 5
        const val COMPARISON_WARMUP_ITERATIONS = 6
        const val COMPARISON_MEASUREMENT_ITERATIONS = 10

        val T0 = Instant.fromEpochMilliseconds(1_000L)
        val T1 = Instant.fromEpochMilliseconds(2_000L)
    }
}
