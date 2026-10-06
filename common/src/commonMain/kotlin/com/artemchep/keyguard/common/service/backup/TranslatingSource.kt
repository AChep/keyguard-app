package com.artemchep.keyguard.common.service.backup

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source

/**
 * Routes the reads and the close of [upstream] through [translate], so the
 * errors of a remote store surface as [BackupObjectStoreException]s.
 */
internal abstract class TranslatingSource(
    private val upstream: Source,
) : RawSource {
    protected abstract fun <T> translate(
        block: () -> T,
    ): T

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = translate {
        upstream.readAtMostTo(sink, byteCount)
    }

    override fun close() {
        translate {
            upstream.close()
        }
    }
}
