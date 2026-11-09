package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import kotlinx.coroutines.awaitCancellation
import java.util.Collections

/**
 * TEST ONLY: the items a [TestRelay] keeps for the devices that are offline, in memory, with
 * the rules of the relay: a request needs a live ticket of the relay, an item is at most as
 * large as its kind allows, a PUT replaces the item there unless it is write-once, see
 * [RelayMailbox.put], a GET with the tag of the item is not modified, and an expired item
 * is gone. Each upload gets a new `ETag`, and a GET with a download that holds the start
 * of the upload gets only the rest, as a 206 carries it, see [RelayMailbox.get].
 */
// The requests of the relay, and what the tests do to its items.
@Suppress("TooManyFunctions")
class TestRelayMailbox(
    /** The limits of the items, as the welcome names them. */
    private val limits: RelayMailboxLimits = RelayMailboxLimits(),
    /** The clock of the relay, in Unix seconds. */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : RelayMailbox {
    /** An item: its [tag], its sealed [body], when it [expiresAt], in Unix seconds, and the [etag] of its upload. */
    class Item(
        val tag: String,
        val body: ByteArray,
        val expiresAt: Long,
        val etag: String,
    )

    /** A request the relay served: its [method] and the [kind] of its item. */
    data class Request(
        val method: String,
        val kind: MailboxKind,
    )

    private val lock = Any()

    private val items = mutableMapOf<Pair<MailboxKind, String>, Item>()

    /** The uploads so far, under the lock: each one names its `ETag`. */
    private var uploads = 0L

    /** The tickets of the live connections of the relay. */
    internal val tickets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Every request with a live ticket, in order. */
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** The item id and the body of every PUT with a live ticket, in order. */
    val putBodies: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())

    /** Set to refuse every PUT so, such as a full relay does. */
    @Volatile
    var putRefusal: RelayMailboxResult? = null

    /** The answers to the next state GETs, in order, in place of what the relay holds. */
    val stateGetAnswers: MutableList<RelayMailboxResult> = Collections.synchronizedList(mutableListOf())

    /**
     * Set to have another device write each blob item just before the next write-once PUT
     * of it does, with the same body, as a race of two uploaders does.
     */
    @Volatile
    var raceBlobPuts: Boolean = false

    /**
     * Set to send at most this many bytes of the body of a blob GET with a download, then
     * stall until the request is cancelled, as a slow link at the end of a background sync.
     */
    @Volatile
    var stallBlobGetsAfter: Int? = null

    /** The blob GETs that stalled so far, see [stallBlobGetsAfter]. */
    @Volatile
    var stalls: Int = 0
        private set

    /** The offset of each GET that resumed a download, as a `Range: bytes=N-` asks. */
    val resumes: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** The write-once PUTs that found their item there already. */
    @Volatile
    var exists: Int = 0
        private set

    /** The items of the [kind] the relay holds, by item id. */
    fun items(kind: MailboxKind): Map<String, Item> = synchronized(lock) {
        items.filterKeys { it.first == kind }.mapKeys { it.key.second }
    }

    /** Drops every item, as a relay short of space does. */
    fun evictAll() {
        synchronized(lock) { items.clear() }
    }

    /** Drops every item of the [kind], as a relay short of space does. */
    fun evict(kind: MailboxKind) {
        synchronized(lock) { items.keys.removeAll { it.first == kind } }
    }

    /**
     * Replaces the body of the item [id] of the [kind] with [body], as a new upload: as a
     * device that deleted the item and wrote garbage under its id does.
     */
    fun replace(kind: MailboxKind, id: String, body: ByteArray) {
        synchronized(lock) {
            val item = checkNotNull(items[kind to id]) { "No item $id" }
            uploads += 1
            val etag = "\"${uploads.toString(16)}-${body.size.toString(16)}\""
            items[kind to id] = Item(item.tag, body.copyOf(), item.expiresAt, etag)
        }
    }

    /** The PUT requests of the [kind] so far. */
    fun puts(kind: MailboxKind): Int = requests.count { it.method == "PUT" && it.kind == kind }

    override suspend fun put(
        ref: RelayMailboxRef,
        tag: String,
        ttlSeconds: Long,
        body: ByteArray,
        ifAbsent: Boolean,
    ): RelayMailboxResult = serve("PUT", ref) {
        putBodies += ref.item to body.copyOf()
        val max = if (ref.kind == MailboxKind.STATE) limits.stateBytes else limits.blobBytes
        val expiresAt = nowSeconds() + ttlSeconds.coerceIn(1L, limits.ttlSeconds)
        // Each upload names a new ETag.
        uploads += 1
        val upload = Item(tag, body.copyOf(), expiresAt, etag = "\"${uploads.toString(16)}-${body.size.toString(16)}\"")
        if (ifAbsent && raceBlobPuts && ref.kind == MailboxKind.BLOB) {
            items[ref.kind to ref.item] = upload
        }
        when {
            body.size > max -> RelayMailboxResult.TooLarge
            // Checked before the quota, as the relay does.
            ifAbsent && live(ref) != null -> {
                exists += 1
                RelayMailboxResult.Exists
            }

            else -> putRefusal ?: run {
                items[ref.kind to ref.item] = upload
                RelayMailboxResult.Done(expiresAt)
            }
        }
    }

    override suspend fun get(
        ref: RelayMailboxRef,
        ifNoneMatch: String?,
        maxBytes: Int,
        download: RelayMailboxDownload?,
    ): RelayMailboxResult {
        var served: Item? = null
        val result = serve("GET", ref) {
            val item = live(ref)
            val answer = if (ref.kind == MailboxKind.STATE) stateGetAnswers.removeFirstOrNull() else null
            when {
                answer != null -> answer
                item == null -> RelayMailboxResult.NotFound
                item.tag == ifNoneMatch -> RelayMailboxResult.NotModified
                item.body.size > maxBytes -> RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE)
                else -> RelayMailboxResult.Found(item.tag, item.expiresAt, item.body.copyOf())
                    .also { served = item }
            }
        }
        val item = served
        return if (item != null && download != null) send(ref, item, download) else result
    }

    /**
     * Sends the [item] into the [download], as the relay answers a GET with `Range` and
     * `If-Range`: the rest of the upload whose start it holds, else all of it.
     */
    private suspend fun send(
        ref: RelayMailboxRef,
        item: Item,
        download: RelayMailboxDownload,
    ): RelayMailboxResult {
        val length = item.body.size
        val resumed = download.isResumable && download.etag == item.etag
        if (resumed && download.size >= length) {
            // 416: the range is past the end of the item.
            download.reset()
            return RelayMailboxResult.Failed(RelayMailboxError.NETWORK)
        }
        if (resumed) {
            resumes += download.size
        } else {
            download.reset()
            download.etag = item.etag
            download.total = length.toLong()
        }
        val from = download.size.toInt()
        val stall = stallBlobGetsAfter?.takeIf { ref.kind == MailboxKind.BLOB }
        val end = stall?.let { minOf(length, from + it) } ?: length
        download.append(item.body.copyOfRange(from, end), end - from)
        if (end < length) {
            stalls += 1
            awaitCancellation()
        }
        return RelayMailboxResult.Found(item.tag, item.expiresAt, download.take())
    }

    override suspend fun head(ref: RelayMailboxRef): RelayMailboxResult = serve("HEAD", ref) {
        live(ref)
            ?.let { RelayMailboxResult.Found(it.tag, it.expiresAt, body = null) }
            ?: RelayMailboxResult.NotFound
    }

    override suspend fun delete(ref: RelayMailboxRef): RelayMailboxResult = serve("DELETE", ref) {
        if (live(ref) == null) {
            RelayMailboxResult.NotFound
        } else {
            items.remove(ref.kind to ref.item)
            RelayMailboxResult.Done()
        }
    }

    /** Returns the item of [ref] unless it expired; under the lock. */
    private fun live(ref: RelayMailboxRef): Item? {
        val key = ref.kind to ref.item
        if (items[key]?.let { it.expiresAt < nowSeconds() } == true) items.remove(key)
        return items[key]
    }

    private fun serve(
        method: String,
        ref: RelayMailboxRef,
        block: () -> RelayMailboxResult,
    ): RelayMailboxResult {
        if (ref.ticket !in tickets) return RelayMailboxResult.Failed(RelayMailboxError.TICKET_REQUIRED)
        requests += Request(method, ref.kind)
        return synchronized(lock) { block() }
    }
}
