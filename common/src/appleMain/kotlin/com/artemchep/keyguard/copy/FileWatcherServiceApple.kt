package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.directorywatcher.FileWatchEvent
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.appleBookmarkResolutionOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkDataOrNull
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingImmediatelyAvailableMetadataOnly
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFilePresenterProtocol
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSFileVersion
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithoutImplicitStartAccessing
import platform.Foundation.NSURLBookmarkResolutionWithoutMounting
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSURLContentModificationDateKey
import platform.Foundation.NSURLFileResourceIdentifierKey
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.NSURLGenerationIdentifierKey
import platform.Foundation.promisedItemResourceValuesForKeys
import platform.darwin.NSObject

/** Observes one selected document per collector; never reads or downloads its contents. */
class FileWatcherServiceApple internal constructor(
    private val pollIntervalMillis: Long,
    private val lifecycle: suspend ((Boolean) -> Unit) -> Nothing,
) : FileWatcherService {
    constructor() : this(DEFAULT_POLL_INTERVAL_MILLIS, ::watchAppleFileWatcherLifecycle)

    init {
        require(pollIntervalMillis > 0L)
    }

    override fun fileChangedFlow(file: LocalPath): Flow<FileWatchEvent> =
        uriChangedFlow(file.toFileUriString(), null)

    override fun uriChangedFlow(uri: String): Flow<FileWatchEvent> =
        uriChangedFlow(uri, null)

    override fun uriChangedFlow(
        uri: String,
        accessToken: FileAccessToken?,
    ): Flow<FileWatchEvent> {
        if (uri.toLocalPathFromFileUriOrNull() == null) {
            return flow { awaitCancellation() }
        }
        return channelFlow {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val observation = AppleFileObservation(uri, accessToken) { wake.trySend(Unit) }
            launch { lifecycle(observation::setForeground) }
            // A native coordination call may block the sampling coroutine. Cancellation
            // must still close the registration gate and cancel pending coordination.
            launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { observation.close() }
                }
            }
            val sampler = AppleFileSampler(observation, pollIntervalMillis) { path, kind ->
                send(FileWatchEvent(LocalPath(path), kind, null))
            }
            try {
                // Coordinated reads block their thread while a writer holds the item.
                withContext(Dispatchers.IO) {
                    while (true) {
                        ensureActive()
                        if (!observation.isForeground()) {
                            sampler.onBackground()
                            wake.receive()
                            continue
                        }
                        val waitMillis = sampler.sample()
                        withTimeoutOrNull(waitMillis) { wake.receive() }
                    }
                }
            } finally {
                observation.close()
                // Sampling has returned before releasing its security scope.
                observation.releaseAccess()
                wake.close()
            }
        }
    }
}

private data class AppleFileSnapshot(
    val path: String,
    val exists: Boolean,
    val identity: Any? = null,
    val generation: Any? = null,
    val modified: Double? = null,
    val size: Long? = null,
)

private class AppleFileObservationException : RuntimeException("Could not observe the selected file.")

/** Turns successive samples into events. Only the sampling coroutine uses it. */
private class AppleFileSampler(
    private val observation: AppleFileObservation,
    private val pollIntervalMillis: Long,
    private val sendEvent: suspend (path: String, kind: FileWatchEvent.Kind) -> Unit,
) {
    private var previous: AppleFileSnapshot? = null
    private var uncertain = false
    private var retryMillis = pollIntervalMillis

    fun onBackground() {
        observation.releaseAccess()
        uncertain = uncertain || previous != null
    }

    /** Returns how long to wait for a wake-up before the next sample. */
    suspend fun sample(): Long {
        val invalidated = observation.consumeInvalidation()
        var waitMillis = pollIntervalMillis
        try {
            val snapshot = observation.sample()
            currentCoroutineContext().ensureActive()
            if (snapshot != null && observation.isForeground()) {
                onSnapshot(snapshot, invalidated)
            } else {
                uncertain = uncertain || invalidated ||
                    (previous != null && !observation.isForeground())
            }
        } catch (_: AppleFileObservationException) {
            // Permission/provider failures are not deletions. Retain the
            // baseline and invalidate once access recovers.
            uncertain = true
            val baseline = previous
            if (invalidated && baseline != null && observation.isForeground()) {
                sendEvent(baseline.path, FileWatchEvent.Kind.MODIFIED)
            }
            waitMillis = retryMillis
            retryMillis = (retryMillis * 2L).coerceAtMost(MAX_RETRY_INTERVAL_MILLIS)
            observation.retryAccess()
        } finally {
            observation.releaseAccessIfSuspended()
        }
        return waitMillis
    }

    private suspend fun onSnapshot(snapshot: AppleFileSnapshot, invalidated: Boolean) {
        val recoveredWithoutBaseline = previous == null && uncertain
        val kind = eventKind(previous, snapshot, invalidated, uncertain)
        previous = snapshot
        uncertain = false
        retryMillis = pollIntervalMillis
        if (kind != null) {
            sendEvent(snapshot.path, kind)
        }
        if (recoveredWithoutBaseline) {
            // Consumers ignore INITIALIZED; recovery must also
            // retry a sync that failed before the first sample.
            sendEvent(snapshot.path, FileWatchEvent.Kind.MODIFIED)
        }
    }
}

/** Returns the event that [snapshot] reports after [previous], or `null` when there is none. */
private fun eventKind(
    previous: AppleFileSnapshot?,
    snapshot: AppleFileSnapshot,
    invalidated: Boolean,
    uncertain: Boolean,
): FileWatchEvent.Kind? = when {
    previous == null -> FileWatchEvent.Kind.INITIALIZED
    !previous.exists && snapshot.exists -> FileWatchEvent.Kind.CREATED
    previous.exists && !snapshot.exists -> FileWatchEvent.Kind.DELETED
    previous != snapshot || invalidated || uncertain -> FileWatchEvent.Kind.MODIFIED
    else -> null
}

/** The sampling coroutine owns [access]; other state is guarded. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class AppleFileObservation(
    uri: String,
    accessToken: FileAccessToken?,
    private val wake: () -> Unit,
) {
    private val lock = SynchronizedObject()
    private var state = State.BACKGROUND
    private var evicted = false
    private var invalidated = false
    private var epoch = 0L
    private var locationVersion = 0L
    private var coordinator: NSFileCoordinator? = null
    private val access = AppleFileAccess(uri, accessToken)
    private val presenter: AppleFilePresenter = AppleFilePresenter(
        changed = changed@{
            synchronized(lock) {
                if (state == State.CLOSED) return@changed
                invalidated = true
            }
            wake()
        },
        moved = { url ->
            synchronized(lock) {
                if (state == State.FOREGROUND) {
                    presenter.url.value = url
                    locationVersion++
                    invalidated = true
                    coordinator?.cancel()
                }
            }
            wake()
        },
        deletion = wake,
        eviction = {
            synchronized(lock) {
                evicted = true
                epoch++
                presenter.unregister()
                coordinator?.cancel()
            }
            // Eviction is not a content edit. Forcing a sync here would immediately
            // download the document the provider is trying to evict.
            wake()
        },
    )

    fun setForeground(value: Boolean) {
        synchronized(lock) {
            if (state == State.CLOSED || (state == State.FOREGROUND) == value) return
            state = if (value) State.FOREGROUND else State.BACKGROUND
            if (!value) {
                epoch++
                invalidated = true
                presenter.unregister()
                coordinator?.cancel()
            } else {
                evicted = false
            }
        }
        wake()
    }

    fun isForeground(): Boolean = synchronized(lock) { state == State.FOREGROUND }

    fun consumeInvalidation(): Boolean = synchronized(lock) {
        invalidated.also { invalidated = false }
    }

    suspend fun sample(): AppleFileSnapshot? {
        val request = prepareRead() ?: return null
        val url = request.url
        val reader = request.coordinator
        val snapshot = try {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                var result: Result<AppleFileSnapshot>? = null
                reader.coordinateReadingItemAtURL(
                    url,
                    options = NSFileCoordinatorReadingImmediatelyAvailableMetadataOnly or
                        NSFileCoordinatorReadingWithoutChanges,
                    error = error.ptr,
                    byAccessor = { coordinatedUrl ->
                        // Kotlin exceptions must not escape an Objective-C callback.
                        result = runCatching {
                            val resolved = coordinatedUrl ?: throw AppleFileObservationException()
                            readAndCommitSnapshot(request, resolved)
                        }
                    },
                )
                // Our own lifecycle/move/eviction cancellation is not a provider error.
                // In particular, eviction must not create a recovery invalidation
                // that immediately starts downloading unchanged contents again.
                synchronized(lock) {
                    if (!isCurrent(request)) return@memScoped null
                    result?.getOrThrow() ?: if (error.value.isMissingFile()) {
                        presenter.unregister()
                        AppleFileSnapshot(requireNotNull(url.path), exists = false)
                    } else {
                        throw AppleFileObservationException()
                    }
                }
            }
        } finally {
            synchronized(lock) { coordinator = null }
        }
        if (snapshot?.exists == false) releaseAccess()
        return snapshot
    }

    private fun readAndCommitSnapshot(request: ReadRequest, url: NSURL): AppleFileSnapshot {
        val snapshot = readSnapshot(url)
        val refreshedBookmark = if (snapshot.exists) access.refreshedBookmark(url) else null
        synchronized(lock) {
            if (isCurrent(request)) {
                if (refreshedBookmark != null) access.updateBookmark(refreshedBookmark, url)
                presenter.url.value = url
                if (snapshot.exists && !evicted) {
                    // Register inside the coordinated baseline read so
                    // no coordinated write can fall in an attachment gap.
                    presenter.register()
                } else if (!snapshot.exists) {
                    presenter.unregister()
                }
            }
        }
        return snapshot
    }

    /** Opens access for the foreground epoch and prepares its read, or returns `null` in the background. */
    private suspend fun prepareRead(): ReadRequest? {
        val currentEpoch = synchronized(lock) {
            epoch.takeIf { state == State.FOREGROUND }
        } ?: return null
        if (!access.isOpen(currentEpoch)) {
            access.release()
            val currentLocationVersion = synchronized(lock) { locationVersion }
            val url = access.open(currentEpoch) { presenter.url.value }
            synchronized(lock) {
                if (locationVersion == currentLocationVersion) presenter.url.value = url
            }
        }
        return suspendCancellableCoroutine { continuation ->
            // Foundation's move tracking requires creation on the presenter's queue.
            // Capture the URL in that same operation, after earlier move callbacks.
            presenter.presentedItemOperationQueue().addOperationWithBlock {
                continuation.resumeWith(runCatching {
                    synchronized(lock) {
                        if (state != State.FOREGROUND || epoch != currentEpoch) return@synchronized null
                        ReadRequest(
                            url = requireNotNull(presenter.url.value),
                            coordinator = NSFileCoordinator(filePresenter = presenter),
                            epoch = currentEpoch,
                            locationVersion = locationVersion,
                        ).also { coordinator = it.coordinator }
                    }
                })
            }
        }
    }

    /** Call with the registration lock held. */
    private fun isCurrent(request: ReadRequest): Boolean = state == State.FOREGROUND &&
        epoch == request.epoch && locationVersion == request.locationVersion

    fun releaseAccessIfSuspended() {
        if (synchronized(lock) { state != State.FOREGROUND || evicted }) releaseAccess()
    }

    fun retryAccess() {
        synchronized(lock) { presenter.unregister() }
        releaseAccess()
    }

    fun releaseAccess() {
        access.release()
    }

    fun close() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            presenter.unregister()
            coordinator?.cancel()
        }
        wake()
    }

    private enum class State { BACKGROUND, FOREGROUND, CLOSED }

    private class ReadRequest(
        val url: NSURL,
        val coordinator: NSFileCoordinator,
        val epoch: Long,
        val locationVersion: Long,
    )
}

/** The access grant and bookmark of one observation. Only the sampling coroutine uses them. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class AppleFileAccess(
    private val uri: String,
    private val accessToken: FileAccessToken?,
) {
    private var grant: Grant? = null
    private var bookmarkData = accessToken?.value?.toSecurityScopedBookmarkDataOrNull()
    private var bookmarkPath: String? = null

    fun isOpen(epoch: Long): Boolean = grant?.epoch == epoch

    /** Starts accessing the bookmarked location or, without a bookmark, [presentedUrl] or the URI. */
    fun open(epoch: Long, presentedUrl: () -> NSURL?): NSURL {
        val url = if (accessToken != null) {
            resolveBookmark()
        } else {
            presentedUrl() ?: NSURL.URLWithString(uri)
                ?: throw AppleFileObservationException()
        }
        if (!url.isFileURL()) throw AppleFileObservationException()
        grant = Grant(url, url.startAccessingSecurityScopedResource(), epoch)
        return url
    }

    private fun resolveBookmark(): NSURL = memScoped {
        val data = bookmarkData ?: throw AppleFileObservationException()
        val stale = alloc<BooleanVar>()
        stale.value = false
        val resolved = NSURL.URLByResolvingBookmarkData(
            data,
            // Retrying an offline location must stay passive. Own the access
            // lifetime explicitly, including iOS's ephemeral bookmark grants.
            appleBookmarkResolutionOptions or NSURLBookmarkResolutionWithoutUI or
                NSURLBookmarkResolutionWithoutMounting or NSURLBookmarkResolutionWithoutImplicitStartAccessing,
            null,
            stale.ptr,
            null,
        )
            ?: throw AppleFileObservationException()
        // A stale bookmark must be replaced while the resolved location is
        // accessible, before a later atomic save removes its old file identity.
        bookmarkPath = resolved.path.takeUnless { stale.value }
        resolved
    }

    fun release() {
        grant?.let {
            if (it.accessing) it.url.stopAccessingSecurityScopedResource()
        }
        grant = null
    }

    fun refreshedBookmark(url: NSURL): NSData? =
        if (accessToken != null && bookmarkPath != url.path) {
            // A failed refresh is retried on the next sample.
            url.bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null)
        } else {
            null
        }

    fun updateBookmark(data: NSData, url: NSURL) {
        bookmarkData = data
        bookmarkPath = url.path
    }

    private class Grant(val url: NSURL, val accessing: Boolean, val epoch: Long)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readSnapshot(url: NSURL): AppleFileSnapshot = memScoped {
    val path = url.path ?: throw AppleFileObservationException()
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    url.removeAllCachedResourceValues()
    // Ordinary resourceValues are not allowed for ImmediatelyAvailableMetadataOnly.
    // Promised metadata does not materialize an evicted provider document. Some
    // providers omit generation; identity, timestamp and size remain the fallback.
    val values = url.promisedItemResourceValuesForKeys(
        listOf(
            NSURLFileResourceIdentifierKey,
            NSURLGenerationIdentifierKey,
            NSURLContentModificationDateKey,
            NSURLFileSizeKey,
        ),
        error.ptr,
    ) ?: if (error.value.isMissingFile()) {
        return@memScoped AppleFileSnapshot(path, exists = false)
    } else {
        throw AppleFileObservationException()
    }
    AppleFileSnapshot(
        path = path,
        exists = true,
        identity = values[NSURLFileResourceIdentifierKey],
        generation = values[NSURLGenerationIdentifierKey],
        modified = (values[NSURLContentModificationDateKey] as? NSDate)?.timeIntervalSinceReferenceDate,
        size = (values[NSURLFileSizeKey] as? NSNumber)?.longLongValue,
    )
}

private fun NSError?.isMissingFile(): Boolean = this != null && domain == NSCocoaErrorDomain &&
    (code == NSFileNoSuchFileError || code == NSFileReadNoSuchFileError)

private class AppleFilePresenter(
    private val changed: () -> Unit,
    private val moved: (NSURL) -> Unit,
    private val deletion: () -> Unit,
    private val eviction: () -> Unit,
) : NSObject(), NSFilePresenterProtocol {
    // Foundation may read presentedItemURL from a thread other than the operation
    // queue, including while add/removeFilePresenter holds the registration lock.
    val url = atomic<NSURL?>(null)
    private val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
    // Guarded by the observation's registration lock.
    private var registered = false

    /** Call only with the registration lock held. */
    fun register() {
        if (!registered) {
            NSFileCoordinator.addFilePresenter(this)
            registered = true
        }
    }

    /** Call only with the registration lock, after closing any relevant gate. */
    fun unregister() {
        if (registered) {
            NSFileCoordinator.removeFilePresenter(this)
            registered = false
        }
    }

    override fun presentedItemURL(): NSURL? = url.value

    override fun presentedItemOperationQueue(): NSOperationQueue = queue

    override fun presentedItemDidChange() = changed()

    override fun presentedItemDidMoveToURL(newURL: NSURL) = moved(newURL)

    override fun presentedItemDidGainVersion(version: NSFileVersion) = changed()

    override fun presentedItemDidLoseVersion(version: NSFileVersion) = changed()

    override fun presentedItemDidResolveConflictVersion(version: NSFileVersion) = changed()

    override fun accommodatePresentedItemDeletionWithCompletionHandler(completionHandler: (NSError?) -> Unit) {
        deletion()
        completionHandler(null)
    }

    override fun accommodatePresentedItemEvictionWithCompletionHandler(completionHandler: (NSError?) -> Unit) {
        // Apple requires removal before acknowledging eviction. Future metadata polls
        // stay passive; presentation resumes on the next foreground observation.
        eviction()
        completionHandler(null)
    }
}

private const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L

private const val MAX_RETRY_INTERVAL_MILLIS = 60_000L
