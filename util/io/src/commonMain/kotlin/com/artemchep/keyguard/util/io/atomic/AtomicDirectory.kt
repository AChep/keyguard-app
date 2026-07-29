package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.bridge.NativeIo
import com.artemchep.keyguard.util.io.bridge.invalidNativeIoResult
import com.artemchep.keyguard.util.io.bridge.isNativeIoFailure

/**
 * One strict portable path component.
 *
 * Separators, dot components, colons, and NUL bytes are rejected so a
 * dynamic identifier can never be reinterpreted as an additional descendant.
 */
class AtomicPathComponent private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AtomicPathComponent && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun parse(value: String): AtomicPathComponent {
            require('/' !in value) {
                "Atomic path component must not contain a separator"
            }
            AtomicRelativePath.parse(value)
            return AtomicPathComponent(value)
        }
    }
}

/**
 * A portable relative path beneath an [AtomicDirectory].
 *
 * `/` is the only separator. Absolute paths, empty components, dot
 * components, backslashes, colons, trailing separators, and NUL bytes are
 * rejected before the native directory handle is consulted.
 */
class AtomicRelativePath private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AtomicRelativePath && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /**
         * Parses [value] as a strict portable descendant file path.
         *
         * @throws IllegalArgumentException if the spelling is ambiguous,
         * absolute, or can escape the retained directory.
         */
        fun parse(value: String): AtomicRelativePath {
            require(
                value.isNotEmpty() &&
                    !value.startsWith('/') &&
                    !value.startsWith('\\') &&
                    !value.endsWith('/') &&
                    !value.endsWith('\\') &&
                    '\\' !in value &&
                    ':' !in value &&
                    '\u0000' !in value &&
                    value.split('/').all { component ->
                        component.isNotEmpty() && component != "." && component != ".."
                    },
            ) {
                "Atomic relative path must be a strict portable descendant file path"
            }
            return AtomicRelativePath(value)
        }

        /**
         * Builds a relative path without reinterpreting any component.
         */
        fun fromComponents(
            first: AtomicPathComponent,
            vararg remaining: AtomicPathComponent,
        ): AtomicRelativePath = AtomicRelativePath(
            buildList {
                add(first.value)
                remaining.forEach { component -> add(component.value) }
            }.joinToString(separator = "/"),
        )
    }
}

/**
 * App-selected trust boundary plus a strict descendant file path.
 *
 * [root] must be an existing stable directory. Missing parent components may
 * be created only within [relativePath]. [path] is lexical and is intended for
 * diagnostics and ordinary reads; atomic publication uses the retained root
 * handle plus [relativePath].
 */
data class AtomicFileDestination(
    val root: LocalPath,
    val relativePath: AtomicRelativePath,
) {
    val path: LocalPath
        get() = atomicDestinationPath(root, relativePath)
}

/**
 * Existing trusted root plus a strict descendant directory path.
 */
data class AtomicDirectoryDestination(
    val root: LocalPath,
    val relativePath: AtomicRelativePath,
) {
    val path: LocalPath
        get() = atomicDestinationPath(root, relativePath)

    fun resolve(
        fileName: AtomicPathComponent,
    ): AtomicFileDestination = AtomicFileDestination(
        root = root,
        relativePath = relativePath.resolve(fileName),
    )

    fun resolveDirectory(
        directoryName: AtomicPathComponent,
    ): AtomicDirectoryDestination = copy(
        relativePath = relativePath.resolve(directoryName),
    )
}

/**
 * Appends one already-validated component.
 */
fun AtomicRelativePath.resolve(
    component: AtomicPathComponent,
): AtomicRelativePath = AtomicRelativePath.parse("$value/${component.value}")

/**
 * A native directory capability selected by resolving [root] exactly once.
 *
 * Symbolic links or reparse points in [root] select the trusted directory and
 * are followed while this object opens. Transactions opened from this
 * capability reject links and mount crossings in every descendant parent
 * component. Renaming or retargeting the lexical root does not redirect a
 * transaction.
 */
@InternalKeyguardIoApi
interface AtomicDirectory : AutoCloseable {
    val root: LocalPath

    /**
     * Opens a transaction at [relativeDestination] beneath the retained root.
     *
     * [AtomicWriteOptions.existingParentLinks] must be
     * [ExistingParentLinkPolicy.Reject].
     */
    fun openAtomicFileTransaction(
        relativeDestination: AtomicRelativePath,
        options: AtomicWriteOptions,
    ): AtomicFileTransaction
}

/**
 * Resolves and retains an existing absolute [root] directory.
 *
 * The returned capability must be closed. Closing it does not invalidate a
 * transaction that was already opened from it.
 */
@InternalKeyguardIoApi
fun openAtomicDirectory(root: LocalPath): AtomicDirectory {
    val packedHandle = NativeIo.directoryOpen(root.value)
    if (isNativeIoFailure(packedHandle) || packedHandle <= 0L) {
        throwNativeIoTransactionFailure(
            packedResult = packedHandle,
            destination = root,
        )
    }
    return NativeAtomicDirectory(
        root = root,
        handle = packedHandle,
    )
}

@InternalKeyguardIoApi
private class NativeAtomicDirectory(
    override val root: LocalPath,
    private val handle: Long,
) : AtomicDirectory {
    private var open = true

    override fun openAtomicFileTransaction(
        relativeDestination: AtomicRelativePath,
        options: AtomicWriteOptions,
    ): AtomicFileTransaction {
        check(open) {
            "Atomic directory is already closed"
        }
        require(options.existingParentLinks == ExistingParentLinkPolicy.Reject) {
            "Retained-directory transactions require ExistingParentLinkPolicy.Reject"
        }
        // Diagnostics only: native publication uses the retained handle plus
        // the already-validated relative spelling, never this lexical path.
        val destination = atomicDestinationPath(root, relativeDestination)
        val packedHandle = NativeIo.txnBeginAtDirectory(
            directoryHandle = handle,
            relativeDestination = relativeDestination.value,
            options = nativeIoTxnOptions(options),
        )
        if (isNativeIoFailure(packedHandle) || packedHandle <= 0L) {
            throwNativeIoTransactionFailure(
                packedResult = packedHandle,
                destination = destination,
            )
        }
        return NativeAtomicFileTransaction(
            destination = destination,
            handle = packedHandle,
            requestedSynchronization = options.synchronization,
        )
    }

    override fun close() {
        if (!open) return
        // The native registry consumes the handle unconditionally, including
        // when it reports the entry busy because another thread still holds it.
        // The flag therefore has to fall before the result is inspected: leaving
        // it set made every later call pass its `check(open)` and then fail
        // against a handle that no longer exists, and made a second close()
        // report a different failure than the first.
        open = false
        val packedResult = NativeIo.directoryClose(handle)
        if (isNativeIoFailure(packedResult)) {
            throwNativeIoTransactionFailure(
                packedResult = packedResult,
                destination = root,
            )
        }
        if (packedResult != 0L) {
            throw invalidNativeIoResult(subject = "directoryClose")
        }
    }
}

private fun atomicDestinationPath(
    root: LocalPath,
    relativePath: AtomicRelativePath,
): LocalPath = LocalPath(
    root.value.trimEnd('/', '\\') + "/" + relativePath.value,
)
