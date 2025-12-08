package com.artemchep.keyguard.common.service.tld.impl

import arrow.core.partially1
import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.tld.TldService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Locale

class TldServiceImpl(
    private val textService: TextService,
    private val logRepository: LogRepository,
) : TldService {
    companion object {
        private const val TAG = "TldService.android"
    }

    override val version: String
        get() = FileHashes.public_suffix_list

    private val dataIo = ::loadTld
        .partially1(textService)
        .measure { duration, node ->
            logRepository.postDebug(TAG) {
                val totalCount = node.count()
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
    ): IO<String> = dataIo
        .effectMap { node ->
            val parts = host.trim().lowercase(Locale.US).split(".").asReversed()
            val length = node.match(parts)
            parts
                // Take N parts of TLD and then one
                // custom domain.
                .take(length + 1)
                .asReversed()
                .joinToString(separator = ".")
        }
        .map { domain ->
            // We could not find an appropriate domain
            // for the host. This means that the host is
            // most likely not valid. For the sake of ease
            // of use - report back the original host as
            // the domain.
            if (domain.isEmpty()) {
                return@map host
            }

            domain
        }
        .measure { duration, result ->
            logRepository.postDebug(TAG) {
                "Found '$result' from the host '$host' in $duration"
            }
        }

    private fun Node.count(): Int = children.values
        .fold(children.values.size) { y, x -> y + x.count() }
}

private data class Node(
    var leaf: Boolean = false,
    val children: MutableMap<String, Node> = mutableMapOf(),
)

/**
 * Loads a TLD list from a local resource file into a
 * hash-tree for more compact footprint & faster search.
 */
private suspend fun loadTld(
    textService: TextService,
) = withContext(Dispatchers.IO) {
    textService
        .readFromResources(FileResource.publicSuffixList).use {
            it
                .bufferedReader()
                .useLines { lines ->
                    val root = Node()
                    lines
                        // Check
                        // https://publicsuffix.org/list/
                        // for formatting rules.
                        .filter { it.isNotEmpty() && !it.startsWith("//") }
                        .forEach { line ->
                            val parts = line.trim().split(".").asReversed()
                            root.append(parts)
                        }
                    root
                }
        }
}

private fun Node.match(parts: List<String>): Int =
    _match(
        parts = parts,
        offset = 0,
    )

private fun Node._match(
    parts: List<String>,
    offset: Int,
): Int {
    if (offset >= parts.size) {
        return if (leaf) offset else -1
    }
    val key = parts[offset]
    val next = children[key]
        ?: children["*"]
        ?: kotlin.run {
            // It only counts as a valid path if the
            // node is a leaf.
            return if (leaf) offset else -1
        }
    return next._match(parts, offset + 1)
        .let {
            it.takeIf { it >= 0 }
                ?: if (leaf) offset else -1
        }
}

private tailrec fun Node.append(parts: List<String>) {
    if (parts.isEmpty()) {
        return
    }
    val key = parts.first()
    val next = getOrPut(key)
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
        next.leaf = true
    }
    next.append(parts = parts.subList(1, parts.size))
}

private fun Node.getOrPut(key: String): Node = children
    .getOrPut(key) { Node() }
