package com.artemchep.keyguard.apple.environments

import com.artemchep.keyguard.common.service.environment.EnvironmentDirectoryAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.EnvironmentMountStatus
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsHost
import com.artemchep.keyguard.util.io.toByteArray
import com.artemchep.keyguard.util.io.toNSData
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationWithSecurityScope
import platform.Foundation.NSURLBookmarkResolutionWithSecurityScope
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSURLBookmarkResolutionWithoutMounting
import platform.Foundation.NSURLBookmarkResolutionWithoutImplicitStartAccessing
import kotlin.io.encoding.Base64

/** Keeps the application's directory scopes open while its broker uses the mounts.
 * Persistent app-scoped bookmarks never cross the helper boundary: each receiver
 * gets an implicit-scope bookmark created while the app's grant is active. */
@OptIn(ExperimentalForeignApi::class)
internal class EnvironmentBookmarkTransfers(
    private val host: EnvironmentMountsHost,
) : EnvironmentDirectoryAccess {
    private val mutex = Mutex()
    private val grants = mutableMapOf<String, Grant>()

    // The connections of the broker share the grants.
    override suspend fun grant(
        mounts: List<EnvironmentMount>,
    ): List<Pair<EnvironmentMount, ByteArray?>> = mutex.withLock {
        val retained = mutableSetOf<String>()
        val granted = mounts.mapNotNull { mount ->
            val bookmark = mount.bookmark ?: return@mapNotNull mount to null
            try {
                val existing = grants[mount.id]?.takeIf { it.source == bookmark && it.path == mount.path }
                val grant = existing ?: open(mount.path, bookmark).also { fresh ->
                    grants.put(mount.id, fresh)?.close()
                }
                retained += mount.id
                if (grant.refreshed != null) {
                    host.refreshBookmark(mount.id, mount.path, grant.refreshed)
                }
                // The answer is wiped once it is sent.
                mount to grant.transfer.copyOf()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                host.setStatus(mount.id, EnvironmentMountStatus.Error)
                // Omission stops any old mount immediately. Never downgrade a
                // failed explicit grant to unscoped path access.
                null
            }
        }
        grants.keys.filter { it !in retained }.forEach { grants.remove(it)?.close() }
        granted
    }

    /** Called only once all packet handlers have joined. */
    fun close() {
        grants.values.forEach(Grant::close)
        grants.clear()
    }

    @Suppress("TooGenericExceptionCaught") // Balance the security scope on every failed transfer.
    private fun open(path: String, bookmark: String): Grant = memScoped {
        val bytes = Base64.Default.decode(bookmark)
        val data = try { bytes.toNSData() } finally { bytes.fill(0) }
        val stale = alloc<BooleanVar> { value = false }
        val url = NSURL.URLByResolvingBookmarkData(
            bookmarkData = data,
            options = NSURLBookmarkResolutionWithSecurityScope or NSURLBookmarkResolutionWithoutUI or
                NSURLBookmarkResolutionWithoutMounting or NSURLBookmarkResolutionWithoutImplicitStartAccessing,
            relativeToURL = null,
            bookmarkDataIsStale = stale.ptr,
            error = null,
        ) ?: error("Directory grant is unavailable")
        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val refreshed = if (stale.value) {
                url.bookmarkDataWithOptions(NSURLBookmarkCreationWithSecurityScope, null, null, null)
                    ?.toByteArray()?.let { data -> try { Base64.Default.encode(data) } finally { data.fill(0) } }
            } else null
            // Zero options preserve the implicit ephemeral security scope. Passing
            // WithSecurityScope would create an app-scoped bookmark for this app.
            val transfer = url.bookmarkDataWithOptions(0u, null, null, null)
                ?.toByteArray()
                ?: error("Directory grant could not be transferred")
            Grant(path, bookmark, url, accessing, transfer, refreshed)
        } catch (e: Throwable) {
            if (accessing) url.stopAccessingSecurityScopedResource()
            throw e
        }
    }

    private class Grant(
        val path: String,
        val source: String,
        val url: NSURL,
        val accessing: Boolean,
        val transfer: ByteArray,
        val refreshed: String?,
    ) {
        fun close() {
            if (accessing) url.stopAccessingSecurityScopedResource()
            transfer.fill(0)
        }
    }
}
