package app.keemobile.kotpass.database

import app.keemobile.kotpass.models.BinaryData
import com.artemchep.keyguard.util.foundation.crypto.sha256
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class BinaryIndexEntry(
    val hash: ByteString,
    val data: BinaryData,
)

class BinaryIndex(
    private val binaries: Map<ByteString, BinaryData>,
) {
    private val hashesByRef: List<ByteString> = binaries.keys.toList()

    private val refsByHash: Map<ByteString, Int> by lazy {
        hashesByRef
            .withIndex()
            .associate { (index, hash) -> hash to index }
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
        hashesByRef.getOrNull(ref)

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
