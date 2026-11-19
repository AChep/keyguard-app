package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlin.io.encoding.Base64
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** What a handle stands for, README section 7. */
internal sealed interface BrowserHandle

/** What an item handle stands for: an item, on the site of the `suggest` or `search` that issued it. */
internal data class BrowserItemHandle(
    val accountId: String,
    val cipherId: String,
    /** The site of the page, see [BrowserPage.site]. */
    val site: String,
    /**
     * Whether the item matched the page: from `suggest`, unless listed only
     * for a passkey; by its URIs from `search`. A release of an unmatched
     * item always prompts.
     */
    val matched: Boolean,
) : BrowserHandle

/** What a passkey handle stands for: a passkey of an item, for its relying party. */
internal data class BrowserPasskeyHandle(
    val accountId: String,
    val cipherId: String,
    /** The credential ID, as the item stores it. */
    val credentialId: String,
    /** The relying party ID of the `suggest` that issued it. */
    val rpId: String,
) : BrowserHandle

/**
 * The handles of one session, README section 7: random strings that stand
 * for an item on a site, or for a passkey of an item. An item keeps its
 * handle on a site for the whole session, one matched and one not; the
 * oldest handles are forgotten past [MAX_HANDLES].
 */
internal class BrowserHandles(
    private val cryptoGenerator: CryptoGenerator,
) {
    private val lock = SynchronizedObject()

    // Guarded by the lock; insertion-ordered, the most recently issued last.
    private val byHandle = linkedMapOf<String, BrowserHandle>()
    private val byItem = mutableMapOf<BrowserHandle, String>()

    /** The handle of [item], the same one it had before in this session. */
    fun issue(item: BrowserHandle): String = synchronized(lock) {
        byItem[item]
            ?.also { handle ->
                // Keeps the handles in use from being forgotten.
                byHandle.remove(handle)
                byHandle[handle] = item
            }
            ?: newHandle().also { handle ->
                byHandle[handle] = item
                byItem[item] = handle
                while (byHandle.size > MAX_HANDLES) {
                    val oldest = byHandle.keys.first()
                    byItem.remove(byHandle.remove(oldest))
                }
            }
    }

    /** What the [handle] stands for, or `null` for one this session did not issue. */
    fun resolve(handle: String): BrowserHandle? = synchronized(lock) {
        byHandle[handle]
    }

    private fun newHandle(): String {
        val bytes = cryptoGenerator.seed(HANDLE_BYTES)
        return Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(bytes)
    }

    companion object {
        /** 32 characters of Base64, well within the 64 of a handle. */
        private const val HANDLE_BYTES = 24

        /** The most handles a session remembers. */
        const val MAX_HANDLES = 4096
    }
}
