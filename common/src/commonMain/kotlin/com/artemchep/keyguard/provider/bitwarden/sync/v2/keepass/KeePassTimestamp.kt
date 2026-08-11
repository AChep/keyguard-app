package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.common.util.to0DigitsNanosOfSecond
import kotlin.time.Instant

/**
 * KeePass persists both KDBX 3 text timestamps and KDBX 4 binary timestamps
 * with whole-second precision. Canonicalizing before encoding lets the KDBX
 * entry, local entity, and last-synced metadata all record the same durable
 * value instead of treating a fractional mismatch as a later remote change.
 */
internal fun Instant.toKeePassTimestamp(): Instant =
    to0DigitsNanosOfSecond()

/**
 * Unsynced local revisions retain their fractional precision, so the KeePass
 * differ must not round them to the whole-second precision used on disk.
 * Microseconds distinguish rapid edits within the same second while retaining
 * enough [Long] range for the distant-past sentinel used by the sync engine;
 * an epoch-nanosecond key would overflow that range.
 */
internal fun Instant.toKeePassDiffKey(): Long =
    epochSeconds * MICROSECONDS_PER_SECOND +
        nanosecondsOfSecond / NANOSECONDS_PER_MICROSECOND

private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val NANOSECONDS_PER_MICROSECOND = 1_000
