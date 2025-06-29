package com.artemchep.keyguard.common.service.hibp.breaches.all.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.service.hibp.breaches.all.model.LocalBreachesEntity
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.random.Random
import kotlin.time.Duration

class BreachesRepositoryImpl(
    private val logRepository: LogRepository,
    private val localDataSource: BreachesLocalDataSource,
    private val remoteDataSource: BreachesRemoteDataSource,
) : BreachesRepository {
    companion object {
        private const val TAG = "BreachesRepository"

        /**
         * After this duration, the app will try
         * to fetch the items again.
         */
        private val CACHE_DURATION = with(Duration) { 7.days }
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        localDataSource = directDI.instance(),
        remoteDataSource = directDI.instance(),
    )

    override fun get(
        forceRefresh: Boolean,
    ): IO<HibpBreachGroup> = localDataSource.get().toIO()
        .flatMap { localEntity ->
            logRepository.postDebug(TAG) {
                "Got breaches from local."
            }
            if (!forceRefresh && localEntity != null) {
                return@flatMap if (localEntity.hasExpired()) {
                    getRemoteAndSave()
                        .handleError { localEntity.model }
                } else {
                    io(localEntity.model)
                }
            }

            getRemoteAndSave()
        }

    /**
     * We are making it fluctuate a bit so when the time comes a
     * user doesn't have to query 100% of the passwords at the same
     * time.
     */
    private fun LocalBreachesEntity.hasExpired() = kotlin.run {
        val age = Clock.System.now() - updatedAt
        age > CACHE_DURATION.times(0.7 + Random.nextDouble(0.3))
    }

    private fun getRemoteAndSave(
    ) = remoteDataSource.get()
        .flatTap { remoteEntity ->
            val newLocalEntity = LocalBreachesEntity(
                updatedAt = Clock.System.now(),
                model = remoteEntity,
            )
            localDataSource.put(newLocalEntity)
                .handleErrorTap {
                    it.printStackTrace()
                }
        }
        .measure { duration, _ ->
            logRepository.postDebug(TAG) {
                "Got breaches from HIBP in $duration."
            }
        }
}
