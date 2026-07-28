package com.artemchep.keyguard.common.service.tld.impl

import arrow.core.partially1
import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.util.foundation.io.useLines
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.tld.TldService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.measureTimedValue

private const val PREFIX_EXCEPTION = "!"

class TldServiceImpl(
    private val textService: TextService,
    private val logRepository: LogRepository,
) : TldService {
    companion object {
        private const val TAG = "TldService"
    }

    override val version: String
        get() = FileHashes.public_suffix_list

    private val dataIo = ::loadTld
        .partially1(textService)
        .measure { duration, node ->
            logRepository.postDebug(TAG) {
                val totalCount = node.descendantCount()
                "Loaded TLD tree in $duration, and it has $totalCount leaves."
            }
        }
        .sharedSoftRef(TAG)

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun getDomainName(
        host: String,
    ): IO<String> = ioEffect {
        val timedValue = measureTimedValue {
            val node = dataIo.bind()
            val domain = kotlin.run {
                val normalizedHost = host.trim().lowercase()
                val start = node
                    .findDomainStart(normalizedHost)
                if (start >= 0) {
                    normalizedHost.substring(start)
                } else {
                    ""
                }
            }
            // We could not find an appropriate domain
            // for the host. This means that the host is
            // most likely not valid. For the sake of ease
            // of use - report back the original host as
            // the domain.
            if (domain.isEmpty()) {
                host
            } else {
                domain
            }
        }

        logRepository.postDebug(TAG) {
            "Found '${timedValue.value}' from the host '$host' " +
                    "in ${timedValue.duration}"
        }
        timedValue.value
    }
}

private class Node(
    var leaf: Boolean = false,
    var exception: Boolean = false,
) {
    private var children: HashMap<String, Node>? = null

    fun findChild(key: String): Node? = children?.get(key)

    fun getOrCreateChild(key: String): Node {
        val children = children
            ?: HashMap<String, Node>(2).also { this.children = it }
        return children.getOrPut(key) { Node() }
    }

    fun descendantCount(): Int {
        val children = children
            ?: return 0
        return children
            .values
            .fold(children.size) { count, child ->
                count + child.descendantCount()
            }
    }
}

/**
 * Loads a TLD list from a local resource file into a
 * hash-tree for more compact footprint & faster search.
 */
private suspend fun loadTld(
    textService: TextService,
) = withContext(Dispatchers.IO) {
    textService
        .readFromResources(FileResource.publicSuffixList)
        .useLines { lines ->
            val root = Node()
            lines
                // Check
                // https://publicsuffix.org/list/
                // for formatting rules.
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("//") }
                .forEach { line ->
                    val exception = line
                        .startsWith(PREFIX_EXCEPTION)
                    val parts = if (exception) {
                        line.substring(PREFIX_EXCEPTION.length)
                    } else {
                        line
                    }
                        .split(".")
                        .asReversed()
                    root.append(
                        parts = parts,
                        exception = exception,
                    )
                }
            root
        }
}

private fun Node.findDomainStart(host: String): Int {
    var node = this
    var labelEnd = host.length
    var fallbackStart = -1

    while (true) {
        val separator = host.lastIndexOf(
            char = '.',
            startIndex = labelEnd - 1,
        )
        val labelStart = separator + 1

        if (node.leaf) {
            // This node remains the prevailing rule if a deeper path fails.
            // The current label is therefore the registrable label to retain.
            fallbackStart = labelStart
        }

        val key = host.substring(labelStart, labelEnd)
        node = node.findChild(key)
            ?: node.findChild("*")
            ?: return fallbackStart

        if (node.exception) {
            // An exception removes its leftmost label from the public suffix,
            // so that label becomes the registrable label in the result.
            return labelStart
        }

        if (separator < 0) {
            return if (node.leaf) {
                0
            } else {
                fallbackStart
            }
        }

        labelEnd = separator
    }
}

private tailrec fun Node.append(
    parts: List<String>,
    exception: Boolean,
) {
    if (parts.isEmpty()) {
        return
    }
    val key = parts.first()
    val next = getOrCreateChild(key)
    // Side effect:
    // Mark the node as the possible leaf of
    // the tree. This means it is one of the
    // possible valid paths.
    //
    // tree:
    //   com
    //    -> linode.members
    // host:
    //   artem.linode.com
    // should output 'linode.com' as a domain because 'linode.com' is not a leaf!
    if (parts.size == 1) {
        if (exception) {
            // Public Suffix List exception rules start with `!`. A matching
            // exception means the public suffix is one label shorter than the
            // listed rule. See https://publicsuffix.org/list/
            next.exception = true
        } else {
            next.leaf = true
        }
    }
    next.append(
        parts = parts.subList(1, parts.size),
        exception = exception,
    )
}
