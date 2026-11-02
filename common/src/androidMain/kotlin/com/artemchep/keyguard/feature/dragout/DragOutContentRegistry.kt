package com.artemchep.keyguard.feature.dragout

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.artemchep.keyguard.common.service.dragout.PreparedContent
import com.artemchep.keyguard.common.service.dragout.PreparedContentReader
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID

/**
 * Process-owned map from capability URIs to prepared content. Provider startup never opens
 * a vault or resolves DI. The content store owns offer expiry and reader quotas; an entry
 * leaves this map, and its URI grant is revoked, when its content retires.
 */
internal object DragOutContentRegistry {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    /** Takes ownership, including when registration fails. */
    @Suppress("TooGenericExceptionCaught")
    fun register(context: Context, content: PreparedContent): Offer {
        try {
            val appContext = context.applicationContext ?: context
            val token = UUID.randomUUID().toString()
            val uri = Uri.parse("content://${dragOutAuthority(appContext.packageName)}/$CONTENT_PATH/$token")
            val extension = content.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val entry = Entry(appContext, token, uri, content, mimeType)
            synchronized(lock) { entries[token] = entry }
            // Runs immediately if the content has already retired.
            content.invokeOnRetirement { retire(entry) }
            check(content.isAvailable()) { "Content has expired" }
            return Offer(entry)
        } catch (failure: Throwable) {
            try {
                content.close()
            } catch (cleanupFailure: Throwable) {
                cleanupFailure.throwIfDragOutFatal()
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    class Offer internal constructor(private val entry: Entry) : AutoCloseable {
        val uri: Uri get() = entry.uri
        val mimeType: String get() = entry.mimeType

        fun handoff() {
            synchronized(lock) { entry.handedOff = true }
            entry.content.handoff()
            check(entry.content.isAvailable()) { "Content offer has expired" }
        }

        /** A rejected drop retires at once; an accepted one keeps its deadline for delayed reads. */
        fun ended(accepted: Boolean) {
            if (!accepted) entry.content.close()
        }

        /** Once handed off, the drop outcome and the store's deadline own retirement. */
        override fun close() {
            val handedOff = synchronized(lock) { entry.handedOff }
            if (!handedOff) entry.content.close()
        }
    }

    data class Metadata(val name: String, val size: Long, val mimeType: String)

    fun metadata(context: Context, uri: Uri): Metadata = live(context, uri)
        .let { entry -> Metadata(entry.content.name, entry.content.size, entry.mimeType) }

    /** Admission is cheap; authentication and reads are deferred to the descriptor's callback thread. */
    // Binder callers receive a redacted availability error, never the backing's cause or private paths.
    @Suppress("SwallowedException")
    fun acquire(context: Context, uri: Uri): DescriptorLease {
        val entry = live(context, uri)
        val reader = try {
            entry.content.openReader()
        } catch (failure: IllegalStateException) {
            throw unavailable()
        }
        return DescriptorLease(entry.content.size, reader)
    }

    class DescriptorLease internal constructor(val size: Long, val reader: PreparedContentReader) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            reader.close()
        }
    }

    internal class Entry(
        val context: Context,
        val token: String,
        val uri: Uri,
        val content: PreparedContent,
        val mimeType: String,
    ) {
        var handedOff = false
    }

    private fun live(context: Context, uri: Uri): Entry {
        val token = dragOutToken(uri.toString(), dragOutAuthority(context.packageName))
        val entry = token?.let { synchronized(lock) { entries[it] } }
        // Never consult the store under this lock: expiry runs retirement callbacks inline.
        if (entry == null || !entry.content.isAvailable()) throw unavailable()
        return entry
    }

    @Suppress("TooGenericExceptionCaught")
    private fun retire(entry: Entry) {
        val removed = synchronized(lock) { entries.remove(entry.token, entry) }
        if (!removed) return
        try {
            entry.context.revokeUriPermission(entry.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: Throwable) {
            failure.throwIfDragOutFatal()
            Log.w("DragOutContent", "Could not revoke an expired content grant")
        }
    }

    private fun unavailable() = FileNotFoundException("Content is unavailable")
}

internal fun dragOutAuthority(packageName: String) = "$packageName.dragout"

private const val CONTENT_PATH = "content"

/** Accept only the precise URI that the producer generated, including its canonical lowercase UUID. */
internal fun dragOutToken(uri: String, authority: String): String? {
    val prefix = "content://$authority/$CONTENT_PATH/"
    if (!uri.startsWith(prefix)) return null
    val token = uri.removePrefix(prefix)
    val canonical = try {
        UUID.fromString(token).toString()
    } catch (_: IllegalArgumentException) {
        null
    }
    return token.takeIf { it == canonical && uri == prefix + canonical }
}

/** Callback cancellation maps to Android I/O errors, while fatal errors must still escape. */
internal fun Throwable.throwIfDragOutFatal() {
    if (this is Error) throw this
}
