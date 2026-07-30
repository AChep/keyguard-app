package app.keemobile.kotpass.database

import okio.Source

fun interface KdbxBinaryContentVisitor {
    /**
     * Visits one uncompressed attachment body. [source] is valid only for the
     * duration of this callback and must not escape it; any unread remainder
     * is drained by the decoder.
     *
     * [declaredLength] is the exact byte length when the container declares it
     * upfront (KDBX 4 inner-header binaries), or `null` when the size is only
     * known once the stream ends (XML-embedded binaries).
     */
    fun visit(source: Source, declaredLength: Long?)
}
