package com.artemchep.keyguard.common.service.urlblock.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.urlblock.UrlBlockExposedSyncer
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class UrlBlockExposedSyncerImpl(
    private val urlBlockRepository: UrlBlockRepository,
    private val urlBlockRepositoryExposed: UrlBlockRepositoryExposed,
    private val logRepository: LogRepository,
) : UrlBlockExposedSyncer {
    companion object {
        private const val TAG = "UrlBlockExposedSyncer"
    }

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        // The vault is the source of truth: the first state also
        // repairs a copy that drifted away from it.
        val exposedRulesFlow = urlBlockRepository.get()
            .map { rules ->
                rules.filter { it.exposed }
            }
            .distinctUntilChanged()

        exposedRulesFlow.collectLatest { rules ->
            runCatchingNonFatal {
                urlBlockRepositoryExposed.replaceAll(rules)
                    .bind()
            }.onFailure { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Failed to sync exposed blocked URIs: ${e.message}",
                    level = LogLevel.ERROR,
                )
            }
        }
    }
}
