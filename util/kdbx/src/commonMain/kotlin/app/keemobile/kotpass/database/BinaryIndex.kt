package app.keemobile.kotpass.database

import app.keemobile.kotpass.models.BinaryData
import com.artemchep.keyguard.util.foundation.crypto.sha256
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class BinaryIndexEntry(
    val hash: ByteString,
    val data: BinaryData,
)

internal class BinaryPool : AbstractMap<ByteString, BinaryData>() {
    private val binaries = linkedMapOf<ByteString, BinaryData>()
    internal val hashesByRef = linkedMapOf<Int, ByteString>()

    override val entries: Set<Map.Entry<ByteString, BinaryData>>
        get() = binaries.entries

    fun add(ref: Int, binary: BinaryData) {
        if (binary.hash !in binaries) {
            binaries[binary.hash] = binary
        }
        hashesByRef[ref] = binary.hash
    }

    fun add(binary: BinaryData): ByteString {
        val existingRef = hashesByRef.entries
            .firstOrNull { (_, hash) -> hash == binary.hash }
        if (existingRef == null) {
            val nextRef = (hashesByRef.keys.maxOrNull() ?: -1) + 1
            add(nextRef, binary)
        }
        return binary.hash
    }
}

internal data class ReferencedBinary(
    val ref: Int,
    val hash: ByteString,
    val data: BinaryData,
)

internal fun Map<ByteString, BinaryData>.referencedBinaries(): List<ReferencedBinary> {
    val references = (this as? BinaryPool)?.hashesByRef
        ?: keys.withIndex().associate { (ref, hash) -> ref to hash }
    return references.entries
        .sortedBy { it.key }
        .mapNotNull { (ref, hash) ->
            get(hash)?.let { binary -> ReferencedBinary(ref, hash, binary) }
        }
}

/**
 * A serialization-local, contiguous binary layout.
 *
 * KDBX 4 references binaries by their physical position in the inner header,
 * so the same plan must be used by both the inner-header and XML writers.
 * Decode-time IDs from [BinaryPool] are used only to establish a stable order;
 * they are never reused as output references.
 */
internal class BinaryWritePlan private constructor(
    val entries: List<ReferencedBinary>,
) {
    val binaries: Map<ByteString, BinaryData> = entries.associateTo(linkedMapOf()) { entry ->
        entry.hash to entry.data
    }
    private val refsByHash = entries.associate { entry -> entry.hash to entry.ref }

    fun refByHash(hash: ByteString): Int? = refsByHash[hash]

    companion object {
        fun create(binaries: Map<ByteString, BinaryData>): BinaryWritePlan {
            val orderedBinaries = linkedMapOf<ByteString, BinaryData>()

            // Preserve the decoded pool order when one exists, while removing
            // sparse IDs and duplicate references from the output layout.
            for ((_, hash, data) in binaries.referencedBinaries()) {
                if (hash !in orderedBinaries) {
                    orderedBinaries[hash] = data
                }
            }

            // A logical binary may be intentionally retained without being
            // referenced. Encoding must not perform implicit cleanup.
            for ((hash, data) in binaries) {
                if (hash !in orderedBinaries) {
                    orderedBinaries[hash] = data
                }
            }

            val entries = orderedBinaries.entries.mapIndexed { ref, (hash, data) ->
                ReferencedBinary(
                    ref = ref,
                    hash = hash,
                    data = data,
                )
            }
            return BinaryWritePlan(entries)
        }
    }
}

class BinaryIndex(
    private val binaries: Map<ByteString, BinaryData>,
) {
    private val hashesByRef: Map<Int, ByteString> = (binaries as? BinaryPool)?.hashesByRef
        ?: binaries.keys.withIndex().associate { (ref, hash) -> ref to hash }

    private val refsByHash: Map<ByteString, Int> by lazy {
        buildMap {
            for ((ref, hash) in hashesByRef) {
                if (hash !in this) {
                    put(hash, ref)
                }
            }
        }
    }

    private val storageHashesByContentSha256: Map<ByteString, ByteString> by lazy {
        buildMap {
            for ((storageHash, binary) in binaries) {
                val contentHash = sha256(binary.getContent())
                    .toByteString()
                if (contentHash !in this) {
                    put(contentHash, storageHash)
                }
            }
        }
    }

    fun hashByRef(ref: Int): ByteString? =
        hashesByRef[ref]

    fun refByHash(hash: ByteString): Int? =
        refsByHash[hash]

    fun getByRef(ref: Int): BinaryIndexEntry? =
        hashByRef(ref)?.let(::getByHash)

    fun getByHash(hash: ByteString): BinaryIndexEntry? =
        binaries[hash]?.let { binary ->
            BinaryIndexEntry(
                hash = hash,
                data = binary,
            )
        }

    fun findByContentSha256(hash: ByteArray): BinaryIndexEntry? =
        findByContentSha256(hash.toByteString())

    fun findByContentSha256(hash: ByteString): BinaryIndexEntry? =
        storageHashesByContentSha256[hash]?.let(::getByHash)
}
