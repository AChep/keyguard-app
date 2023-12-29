package com.artemchep.keyguard.common.service.hibp.passwords.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceRemote
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.data.pwnage.PasswordBreach
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.random.Random
import kotlin.time.Duration

class PasswordPwnageRepositoryImpl(
    private val logRepository: LogRepository,
    private val localDataSource: PasswordPwnageDataSourceLocal,
    private val remoteDataSource: PasswordPwnageDataSourceRemote,
) : PasswordPwnageRepository {
    companion object {
        private const val TAG = "PasswordPwnage"

        /**
         * After this duration, the app will try
         * to fetch the items again.
         */
        private val CACHE_DURATION = with(Duration) { 21.days }
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        localDataSource = directDI.instance(),
        remoteDataSource = directDI.instance(),
    )

    override fun checkOne(
        password: String,
        cache: Boolean,
    ): IO<PasswordPwnage> = localDataSource
        .getOne(password)
        .handleError { null }
        .flatMap { localPwnage ->
            localPwnage?.let { entity ->
                val localIo = io(entity)
                    .effectTap { pwnage ->
                        val msg = kotlin.run {
                            val passwordPrefix = password.take(2)
                            val passwordOccurrences = pwnage.count
                            "Obtained local pwnage report for '$passwordPrefix****', $passwordOccurrences occurrences."
                        }
                        logRepository.post(TAG, msg)
                    }
                    .map {
                        PasswordPwnage(
                            occurrences = it.count.toInt(),
                        )
                    }

                if (entity.hasExpired()) {
                    checkRemote(password, cache = cache)
                        .handleErrorWith {
                            localIo
                        }
                } else {
                    localIo
                }
            }
            // Otherwise check the remote data source and
            // store the result in the local one.
                ?: checkRemote(password, cache = cache)
        }

    override fun checkMany(
        passwords: Set<String>,
        cache: Boolean,
    ): IO<Map<String, PasswordPwnage?>> = ioEffect {
        // According to
        // https://stackoverflow.com/a/49960506/1408535
        // maximum number of query parameters is usually
        // 999 per query. We do want to be safe, so we use a
        // lower value.
        val bucketSize = 100
        val entities = passwords
            .windowed(
                size = bucketSize,
                step = bucketSize,
                partialWindows = true,
            )
            .flatMap { passwordsWindow ->
                localDataSource
                    .getMany(passwordsWindow)
                    // On an error, fake a response with
                    // no data returned.
                    .handleError {
                        passwordsWindow
                            .associateWith { null }
                    }
                    .bind()
                    .toList()
            }
            .toMap()

        val out = mutableMapOf<String, PasswordPwnage?>()
        val pending = mutableListOf<IO<Pair<String, Either<Throwable, PasswordPwnage>>>>()
        entities.entries.forEach { (password, entity) ->
            if (entity != null) {
                val localPwnage = PasswordPwnage(
                    occurrences = entity.count.toInt(),
                )

                if (entity.hasExpired()) {
                    pending += checkRemote(password, cache = cache)
                        .handleError {
                            localPwnage
                        }
                        .attempt()
                        .map { password to it }
                } else {
                    // No need to do any requests, just reuse the
                    // cached report.
                    out[password] = localPwnage
                }
            } else {
                pending += checkRemote(password, cache = cache)
                    .attempt()
                    .map { password to it }
            }
        }

        val q = out.size
        val s = pending.size

        val z = pending
            .parallel(
                context = Dispatchers.IO,
                parallelism = 8,
            )
            .bind()
        z.forEach { (password, result) ->
            val pwnage = result.getOrNull()
            out[password] = pwnage
        }

        out
    }

    /**
     * We are making it fluctuate a bit so when the time comes a
     * user doesn't have to query 100% of the passwords at the same
     * time.
     */
    private fun PasswordBreach.hasExpired() = kotlin.run {
        val age = Clock.System.now() - updatedAt
        age > CACHE_DURATION.times(0.7 + Random.nextDouble(0.3))
    }

    private fun checkRemote(
        password: String,
        cache: Boolean = true,
    ) = remoteDataSource
        .check(password)
        .effectTap { remotePwnage ->
            if (cache) {
                val now = Clock.System.now()
                val count = remotePwnage.occurrences.toLong()
                val entity = PasswordBreach(
                    password = password,
                    count = count,
                    updatedAt = now,
                )
                localDataSource
                    .put(entity)
                    .bind()
            }
        }
        .measure { duration, remotePwnage ->
            logRepository.postDebug(TAG) {
                val prefix = password.take(4)
                val count = remotePwnage.occurrences
                "Obtained remote pwnage report for '$prefix****': $count occurrences, took $duration."
            }
        }
}