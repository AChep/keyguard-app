package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cache owned by one detail observation, independent of reveal/download updates. */
internal fun Flow<Set<VaultUriIcon.App>>.resolveUriAppIcons(
    lookup: suspend (VaultUriIcon.App) -> String?,
): Flow<Map<VaultUriIcon.App, String?>> = channelFlow {
    val cache = mutableMapOf<VaultUriIcon.App, String?>()
    val mutex = Mutex()
    map { sources -> sources.filterTo(mutableSetOf()) { it.enabled } }
        .distinctUntilChanged()
        .collectLatest { sources ->
            // Emit immediately, including when the preference is disabled. The
            // previous lookups are cancelled by collectLatest before this runs.
            send(mutex.withLock { cache.filterKeys { it in sources } })
            val pending = mutex.withLock { sources.filterNot { cache.containsKey(it) } }
            coroutineScope {
                pending.forEach { source ->
                    launch {
                        val url = try {
                            lookup(source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        // Cache misses too; a failed lookup must not be retried
                        // when a different field or URI changes.
                        mutex.withLock {
                            cache[source] = url
                            send(cache.filterKeys { it in sources })
                        }
                    }
                }
            }
        }
}
