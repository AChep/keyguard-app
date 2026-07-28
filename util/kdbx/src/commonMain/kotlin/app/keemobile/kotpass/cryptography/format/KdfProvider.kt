package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.database.header.KdfParameters

interface KdfProvider {
    /**
     * Transforms `compositeKey` using key-derivation function.
     *
     * @param kdfParameters Describes key-derivation function type and parameters.
     * @param compositeKey Key bytes.
     */
    fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray
}
