package com.artemchep.keyguard.util.ffi

private const val KIND_SHIFT: Int = 8
private const val DOMAIN_SHIFT: Int = 16
private const val RAW_CODE_SHIFT: Int = 24
private const val RESERVED_SHIFT: Int = 56
private const val BYTE_MASK: Long = 0xffL

/** The bridge code of an argument that broke the ABI contract. */
private const val BRIDGE_CODE_INVALID_ARGUMENT: Int = 1

/**
 * A failure scalar of `keyguard_ffi::pack_failure`, split into its fields:
 *
 * | bits   | field                                        |
 * |--------|----------------------------------------------|
 * | 0..7   | [operation], numbered by each bridge         |
 * | 8..15  | [kind], a `keyguard_ffi::FailureKind`        |
 * | 16..23 | [domain], a `keyguard_ffi::ErrorDomain`      |
 * | 24..55 | [rawCode], a `u32` kept as its bits          |
 * | 56..62 | reserved, zero unless a bridge assigns a bit |
 * | 63     | failure marker, always set                   |
 *
 * Each bridge maps the fields to its own failures.
 */
data class PackedFailure(
    val operation: Int,
    val kind: Int,
    val domain: Int,
    val rawCode: Int,
) {
    companion object {
        /** Bits 56..62, which `pack_failure` keeps clear. */
        const val RESERVED_BITS: Long = 0x7fL shl RESERVED_SHIFT

        /** The operation of a failure of the ABI adapter itself. */
        const val OPERATION_BRIDGE: Int = 0

        /** `FailureKind::InvalidInput`. */
        const val KIND_INVALID_INPUT: Int = 8

        /** `FailureKind::Internal`. */
        const val KIND_INTERNAL: Int = 12

        /** `ErrorDomain::Bridge`: the raw code is a bridge code. */
        const val DOMAIN_BRIDGE: Int = 3

        /**
         * `keyguard_ffi::BRIDGE_INVALID_ARGUMENT`. A bridge answers it for an argument it
         * rejects before the native call, so that every platform fails alike.
         */
        const val BRIDGE_INVALID_ARGUMENT: Long =
            (1L shl 63) or
                (BRIDGE_CODE_INVALID_ARGUMENT.toLong() shl RAW_CODE_SHIFT) or
                (DOMAIN_BRIDGE.toLong() shl DOMAIN_SHIFT) or
                (KIND_INVALID_INPUT.toLong() shl KIND_SHIFT) or
                OPERATION_BRIDGE.toLong()

        /**
         * Returns the fields of [packed], or `null` when it is not a failure: not negative,
         * or with a bit of [reservedBits] set. A bridge that assigns a reserved bit, as
         * `util/io` does bit 56, passes the others and reads its bit itself.
         */
        fun decode(packed: Long, reservedBits: Long = RESERVED_BITS): PackedFailure? {
            if (packed >= 0L || (packed and reservedBits) != 0L) return null
            return PackedFailure(
                operation = packed.byteAt(0),
                kind = packed.byteAt(KIND_SHIFT),
                domain = packed.byteAt(DOMAIN_SHIFT),
                // `toInt` keeps the low 32 bits: bits 24..55.
                rawCode = (packed ushr RAW_CODE_SHIFT).toInt(),
            )
        }
    }
}

private fun Long.byteAt(shift: Int): Int = ((this ushr shift) and BYTE_MASK).toInt()
