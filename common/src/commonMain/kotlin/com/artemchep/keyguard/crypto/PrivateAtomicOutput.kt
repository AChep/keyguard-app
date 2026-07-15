package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

/**
 * Owner-only same-directory output which becomes visible at [destination] only on [commit].
 */
internal interface PrivateAtomicOutput : AutoCloseable {
    val destination: LocalPath

    fun sink(): Sink

    fun commit()
}

internal fun createPrivateAtomicOutput(
    destination: LocalPath,
): PrivateAtomicOutput {
    val destinationPath = destination.toKotlinxIoPath()
    destinationPath.parent?.let(SystemFileSystem::createDirectories)
    return LocalPathPrivateAtomicOutput(
        destination = destination,
        temporary = createPrivateTemporarySibling(destination),
    )
}

internal expect fun createPrivateTemporarySibling(
    destination: LocalPath,
): LocalPath

private class LocalPathPrivateAtomicOutput(
    override val destination: LocalPath,
    private val temporary: LocalPath,
) : PrivateAtomicOutput {
    private var outputSink: Sink? = null
    private var sinkClaimed = false
    private var committed = false
    private var closed = false

    override fun sink(): Sink {
        check(!closed) { "Private atomic output is closed" }
        check(!committed) { "Private atomic output is committed" }
        check(!sinkClaimed) { "Private atomic output sink has already been acquired" }
        sinkClaimed = true
        return SystemFileSystem.sink(temporary.toKotlinxIoPath())
            .buffered()
            .also { outputSink = it }
    }

    override fun commit() {
        check(!closed) { "Private atomic output is closed" }
        check(!committed) { "Private atomic output is already committed" }
        check(sinkClaimed) { "Private atomic output sink has not been acquired" }

        outputSink?.flush()
        outputSink?.close()
        SystemFileSystem.atomicMove(
            source = temporary.toKotlinxIoPath(),
            destination = destination.toKotlinxIoPath(),
        )
        committed = true
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            outputSink?.close()
        } catch (e: Throwable) {
            failure = e
        }
        if (!committed) {
            try {
                SystemFileSystem.delete(
                    path = temporary.toKotlinxIoPath(),
                    mustExist = false,
                )
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }
}
