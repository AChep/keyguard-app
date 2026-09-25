package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.io.bind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists restorable top-level router stacks and exposes a synchronous snapshot after hydration. */
class NavigationRestoreRepository(
    private val persistence: NavigationStackPersistence,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<String, List<RouteDescriptor>>>(emptyMap())
    private val saveRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val initialization = scope.launch {
        cache.value = runCatching {
            persistence.load(NavigationStackPersistence.Format.Compose)
        }.getOrDefault(emptyMap())
    }

    /** The persisted stack for [routerId], or null if none / empty. Synchronous. */
    fun peek(routerId: String): List<RouteDescriptor>? =
        cache.value[routerId]?.takeIf { it.isNotEmpty() }

    /** Records [descriptors] for [routerId] and persists the whole map asynchronously. */
    fun save(routerId: String, descriptors: List<RouteDescriptor>) {
        // Record submission order before dispatching to the background scope.
        val revision = saveRevisions.updateAndGet { revisions ->
            revisions + (routerId to ((revisions[routerId] ?: 0L) + 1L))
        }.getValue(routerId)
        scope.launch {
            initialization.join()
            mutex.withLock {
                if (saveRevisions.value[routerId] != revision) return@withLock
                val current = cache.value
                val updated = if (descriptors.isEmpty()) {
                    current - routerId
                } else {
                    current + (routerId to descriptors)
                }
                runCatching {
                    persistence.save(NavigationStackPersistence.Format.Compose, updated).bind()
                    cache.value = persistence.load(NavigationStackPersistence.Format.Compose)
                }
            }
        }
    }
}
