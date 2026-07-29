package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.crypto.privateTemporaryStorageDirectory
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.SweepStatus
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Reclaims stale Keyguard temporary artifacts from application-owned roots.
 */
fun interface TemporaryArtifactMaintenance {
    suspend operator fun invoke()
}

internal data class TemporaryArtifactRoot(
    val label: String,
    val provideDirectory: suspend () -> LocalPath?,
)

// These extension boundaries may throw arbitrary non-fatal failures. Maintenance must
// isolate and report them while still propagating cancellation and fatal runtime errors.
@Suppress("TooGenericExceptionCaught")
internal class TemporaryArtifactMaintenanceImpl(
    private val roots: List<TemporaryArtifactRoot>,
    private val sweeper: suspend (LocalPath, Duration) -> SweepReport,
    private val logRepository: LogRepository,
    private val retryDelay: suspend (Duration) -> Unit = { duration ->
        delay(duration)
    },
    private val olderThan: Duration = DEFAULT_ARTIFACT_AGE,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) : TemporaryArtifactMaintenance {
    init {
        require(!olderThan.isNegative()) {
            "Temporary artifact age must not be negative."
        }
        require(retryDelays.none(Duration::isNegative)) {
            "Temporary artifact retry delays must not be negative."
        }
        require(roots.all { root -> root.label.isNotBlank() }) {
            "Temporary artifact root labels must not be blank."
        }
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        roots = buildList {
            add(
                TemporaryArtifactRoot(
                    label = PRIVATE_ROOT_LABEL,
                    provideDirectory = {
                        withContext(Dispatchers.IO) {
                            privateTemporaryStorageDirectory()
                        }
                    },
                ),
            )
            directDI.instanceOrNull<CacheDirProvider>()?.let { cacheDirProvider ->
                add(
                    TemporaryArtifactRoot(
                        label = CACHE_ROOT_LABEL,
                        provideDirectory = cacheDirProvider::get,
                    ),
                )
            }
            addAll(platformTemporaryArtifactRoots())
        },
        sweeper = { directory, olderThan ->
            withContext(Dispatchers.IO) {
                sweepTemporaryArtifacts(
                    directory = directory,
                    olderThan = olderThan,
                )
            }
        },
        logRepository = directDI.instance(),
    )

    override suspend fun invoke() {
        val resolvedRoots = resolveRoots()
        coroutineScope {
            resolvedRoots
                .map { root ->
                    async {
                        sweepWithRetry(root)
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun resolveRoots(): List<ResolvedTemporaryArtifactRoot> {
        val rootsByDirectory = linkedMapOf<LocalPath, ResolvedTemporaryArtifactRoot>()
        for (root in roots) {
            val resolved = resolveRoot(root) ?: continue
            val existing = rootsByDirectory[resolved.directory]
            if (existing == null) {
                rootsByDirectory[resolved.directory] = resolved
            } else {
                logSafely(
                    level = LogLevel.INFO,
                    message = "event=root_deduplicated root=${root.label} " +
                        "duplicateOf=${existing.label}",
                )
            }
        }
        return rootsByDirectory.values.toList()
    }

    private suspend fun resolveRoot(
        root: TemporaryArtifactRoot,
    ): ResolvedTemporaryArtifactRoot? = try {
        root.provideDirectory()?.let { directory ->
            ResolvedTemporaryArtifactRoot(
                label = root.label,
                directory = directory,
            )
        }
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        logSafely(
            level = LogLevel.WARNING,
            message = "event=root_resolution_failed root=${root.label} " +
                "reason=${e.safeTypeName()}",
        )
        null
    }

    private suspend fun sweepWithRetry(root: ResolvedTemporaryArtifactRoot) {
        val maxAttempts = retryDelays.size + 1
        for (attemptIndex in 0 until maxAttempts) {
            val attempt = attemptIndex + 1
            val shouldRetry = try {
                val report = sweeper(root.directory, olderThan)
                val retry = report.shouldRetry()
                logSafely(
                    level = if (retry) LogLevel.WARNING else LogLevel.INFO,
                    message = report.toLogMessage(
                        rootLabel = root.label,
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        retry = retry && attempt < maxAttempts,
                    ),
                )
                retry
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()
                logSafely(
                    level = LogLevel.WARNING,
                    message = "event=sweep_failed root=${root.label} " +
                        "attempt=$attempt maxAttempts=$maxAttempts " +
                        "reason=${e.safeTypeName()} retry=${attempt < maxAttempts}",
                )
                true
            }

            if (!shouldRetry) {
                return
            }
            if (attempt >= maxAttempts) {
                logSafely(
                    level = LogLevel.WARNING,
                    message = "event=sweep_retry_exhausted root=${root.label} " +
                        "attempts=$maxAttempts",
                )
                return
            }
            if (!awaitRetryDelay(root.label, retryDelays[attemptIndex])) {
                break
            }
        }
    }

    private suspend fun awaitRetryDelay(
        rootLabel: String,
        duration: Duration,
    ): Boolean = try {
        retryDelay(duration)
        true
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        logSafely(
            level = LogLevel.WARNING,
            message = "event=retry_delay_failed root=$rootLabel " +
                "reason=${e.safeTypeName()}",
        )
        false
    }

    private suspend fun logSafely(
        level: LogLevel,
        message: String,
    ) {
        try {
            logRepository.add(
                tag = LOG_TAG,
                message = message,
                level = level,
            )
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
        }
    }

    private companion object {
        const val LOG_TAG = "TemporaryArtifactMaintenance"
        const val PRIVATE_ROOT_LABEL = "private-temporary"
        const val CACHE_ROOT_LABEL = "cache"

        val DEFAULT_ARTIFACT_AGE = 24.hours
        val DEFAULT_RETRY_DELAYS = listOf(
            1.minutes,
            5.minutes,
        )
    }
}

private data class ResolvedTemporaryArtifactRoot(
    val label: String,
    val directory: LocalPath,
)

private fun SweepReport.shouldRetry(): Boolean =
    status != SweepStatus.Complete ||
        skippedBusy > 0uL ||
        skippedChanged > 0uL

private fun SweepReport.toLogMessage(
    rootLabel: String,
    attempt: Int,
    maxAttempts: Int,
    retry: Boolean,
): String {
    val failure = firstFailure
    return "event=sweep_result root=$rootLabel attempt=$attempt maxAttempts=$maxAttempts " +
        "status=$status entriesSeen=$entriesSeen candidateNames=$candidateNames " +
        "removed=$removed skippedYoung=$skippedYoung skippedBusy=$skippedBusy " +
        "skippedUnsafe=$skippedUnsafe skippedChanged=$skippedChanged " +
        "inspectionFailed=$inspectionFailed removalFailed=$removalFailed " +
        "firstFailureKind=${failure?.kind ?: "none"} " +
        "firstFailureDomain=${failure?.diagnostic?.domain ?: "none"} " +
        "firstFailureCode=${failure?.diagnostic?.code ?: "none"} retry=$retry"
}

private fun Throwable.safeTypeName(): String =
    this::class.simpleName ?: "unknown"
