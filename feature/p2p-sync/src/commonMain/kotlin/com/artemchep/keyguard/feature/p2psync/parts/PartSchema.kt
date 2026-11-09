package com.artemchep.keyguard.feature.p2psync.parts

/**
 * A JSON pointer (RFC 6901) into an object, such as `/data/apiKey`. Only object members
 * are addressed; array indices are not supported.
 */
data class JsonPointer(val segments: List<String>) {
    override fun toString(): String = segments.joinToString(separator = "") { "/" + escape(it) }

    companion object {
        /** Parses the text form, e.g. `/data/apiKey`. */
        fun parse(text: String): JsonPointer {
            require(text.startsWith('/')) { "A JSON pointer starts with '/': '$text'" }
            return JsonPointer(text.substring(1).split('/').map(::unescape))
        }

        /** Escapes one segment: `~` becomes `~0` and `/` becomes `~1`. */
        fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

        /** Reverts [escape]. */
        fun unescape(segment: String): String = segment.replace("~1", "/").replace("~0", "~")
    }
}

/**
 * A part: the JSON [paths] of a body that change together and merge as one value. Of two
 * concurrent versions, the value with the newer part stamp wins.
 *
 * @property key the part key used in [com.artemchep.keyguard.feature.p2psync.model.EntryVersion.parts].
 */
data class PartSpec(
    val key: String,
    val paths: List<JsonPointer>,
) {
    init {
        require(paths.isNotEmpty()) { "Part '$key' has no paths" }
    }

    companion object {
        /** A part of one path whose key is the path itself. */
        fun of(path: String): PartSpec = PartSpec(key = path, paths = listOf(JsonPointer.parse(path)))
    }
}

/**
 * How a body splits into parts. Everything not covered by a part is the remainder, which
 * changes as one more part, see [com.artemchep.keyguard.feature.p2psync.model.EntryVersion.REMAINDER].
 */
data class PartSchema(
    val parts: List<PartSpec>,
) {
    init {
        val keys = parts.map { it.key }
        require(keys.size == keys.toSet().size) { "Part keys must be unique" }
    }

    /** Every path of every part. */
    val paths: List<JsonPointer> get() = parts.flatMap { it.paths }

    /** Returns the part with the [key], if any. */
    fun part(key: String): PartSpec? = parts.firstOrNull { it.key == key }
}
