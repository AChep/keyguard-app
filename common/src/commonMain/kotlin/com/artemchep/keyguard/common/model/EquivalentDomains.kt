package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.lang.ref.WeakReference
import java.util.Locale

data class EquivalentDomains(
    val domains: Map<String, List<String>>,
) {
    fun findEqDomains(domain: String): List<String> {
        val domainLowerCase = domain.lowercase(Locale.US)
        return domains[domainLowerCase] ?: listOf(domain)
    }
}

class EquivalentDomainsBuilderFactory(
    private val logRepository: LogRepository,
    private val getEquivalentDomains: GetEquivalentDomains,
) {
    companion object {
        private const val TAG = "EqDomainsFactory"
    }

    private var ref = WeakReference<EquivalentDomainsBuilder?>(null)

    constructor(
        directDI: DirectDI,
    ) : this(
        logRepository = directDI.instance(),
        getEquivalentDomains = directDI.instance(),
    )

    fun build(
    ): EquivalentDomainsBuilder {
        val existingBuilder = ref.get()
        if (existingBuilder != null) {
            logRepository.postDebug(TAG) {
                "Reused exiting equivalent domains builder!"
            }
            return existingBuilder
        }

        return synchronized(this) {
            val existingBuilder2 = ref.get()
            if (existingBuilder2 != null) {
                logRepository.postDebug(TAG) {
                    "Reused exiting equivalent domains builder!"
                }
                return existingBuilder2
            }

            logRepository.postDebug(TAG) {
                "Created a new equivalent domains builder!"
            }

            val builder = createNewEquivalentDomainsBuilder()
            ref = WeakReference(builder)
            builder
        }
    }

    private fun createNewEquivalentDomainsBuilder(
    ): EquivalentDomainsBuilder {
        val sharedAllEquivalentDomains = getEquivalentDomains()
            .toIO()
            .shared("AllEquivalentDomains")
        return EquivalentDomainsBuilder(
            logRepository = logRepository,
            sharedAllEquivalentDomains = sharedAllEquivalentDomains,
        )
    }
}

class EquivalentDomainsBuilder(
    private val logRepository: LogRepository,
    private val sharedAllEquivalentDomains: IO<List<DEquivalentDomains>>,
) {
    companion object {
        private const val TAG = "EqDomainsBuilder"
    }

    private val mutex = Mutex()

    private var state = persistentMapOf<String, IO<EquivalentDomains>>()

    suspend fun getAndCache(
        accountId: String,
    ): EquivalentDomains {
        // Fast path:
        // the corresponding task already exists
        val existingTask = state[accountId]
        if (existingTask != null) {
            return existingTask
                .bind()
        }

        return mutex.withLock {
            val existingTaskV2 = state[accountId]
            if (existingTaskV2 != null) {
                return@withLock existingTaskV2
            }

            logRepository.postDebug(TAG) {
                "Building optimized equivalent domains for '$accountId' account."
            }

            val task = sharedAllEquivalentDomains
                .effectMap { allEquivalentDomains ->
                    val equivalentDomains = allEquivalentDomains
                        .asSequence()
                        .filter { !it.excluded && it.accountId == accountId }
                        .flatMap { entry ->
                            val list = entry.domains
                            list
                                .asSequence()
                                .map { d ->
                                    d to list
                                }
                        }
                        .toMap()
                    EquivalentDomains(
                        domains = equivalentDomains,
                    )
                }
                .shared("AccountEquivalentDomains")

            state = state.put(accountId, task)
            task
        }.bind()
    }
}
