@file:Suppress("MagicNumber") // ZIP header offsets, signatures, flags, and CRC polynomial.

package com.artemchep.keyguard.util.zip

/** Parses metadata only; never inflates entries or trusts sizes as allocation hints. */
internal fun readZipDirectory(bytes: ByteArray, maxEntries: Int): List<ZipArchiveEntry> =
    ZipDirectory(bytes, maxEntries).read()

private data class DirectoryBounds(val count: Long, val start: Int, val end: Int)

private class CentralEntry(val position: Int, val nameBytes: ByteArray, var name: String) {
    var compressed = 0L
    var expanded = 0L
    var offset = 0L
    var disk = 0L
    var method = 0
    var zip64 = false
}

@Suppress("TooManyFunctions") // Keep the binary cursor and its format-specific readers together.
private class ZipDirectory(private val bytes: ByteArray, private val maxEntries: Int) {
    fun read(): List<ZipArchiveEntry> {
        val bounds = directoryBounds()
        if (bounds.count > maxEntries || maxEntries < 0) {
            throw ZipArchiveException(ZipArchiveError.TooManyEntries)
        }
        var cursor = bounds.start
        val names = mutableSetOf<String>()
        val rawNames = mutableSetOf<List<Byte>>()
        val entries = ArrayList<ZipArchiveEntry>()
        repeat(bounds.count.toInt()) { id ->
            val (entry, nameBytes, next) = readEntry(cursor, id, bounds)
            if (!names.add(entry.path)) invalid()
            if (!rawNames.add(nameBytes.toList())) invalid()
            entries += entry
            cursor = next
        }
        if (cursor != bounds.end) invalid()
        return entries
    }

    private fun directoryBounds(): DirectoryBounds {
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: invalid()
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)) unsupported()
        val count = u16(end + 10).toLong()
        val size = u32(end + 12)
        val offset = u32(end + 16)
        // Small ZIP64 archives may retain ordinary values in the classic end
        // record. A locator must sit after the directory, not inside its data.
        val locator = end - 20
        val hasZip64Locator = locator >= 0 && offset + size <= locator && u32(locator) == 0x07064b50L
        val needsZip64 = count == 0xffffL || size == 0xffffffffL || offset == 0xffffffffL
        return if (hasZip64Locator || needsZip64) {
            zip64Bounds(end)
        } else bounds(count, offset, size, end)
    }

    private fun zip64Bounds(end: Int): DirectoryBounds {
        val locator = end - 20
        if (u32(locator) != 0x07064b50L || u32(locator + 4) != 0L || u32(locator + 16) != 1L) unsupported()
        val zip64 = index(u64(locator + 8))
        if (u32(zip64) != 0x06064b50L || u64(zip64 + 4) < 44L) invalid()
        if (u64(zip64 + 4) != (locator - zip64 - 12).toLong()) invalid()
        if (u32(zip64 + 16) != 0L || u32(zip64 + 20) != 0L) unsupported()
        val count = u64(zip64 + 32)
        if (u64(zip64 + 24) != count) unsupported()
        return bounds(count, u64(zip64 + 48), u64(zip64 + 40), zip64)
    }

    private fun bounds(count: Long, offset: Long, size: Long, end: Int): DirectoryBounds {
        if (offset > end || size != end.toLong() - offset) invalid()
        return DirectoryBounds(count, index(offset), end)
    }

    private fun readEntry(cursor: Int, id: Int, bounds: DirectoryBounds): Triple<ZipArchiveEntry, ByteArray, Int> {
        if (cursor > bounds.end - 46 || u32(cursor) != 0x02014b50L) invalid()
        val flags = u16(cursor + 8)
        val nameSize = u16(cursor + 28)
        val extraStart = cursor + 46 + nameSize
        val extraEnd = extraStart + u16(cursor + 30)
        val next = extraEnd + u16(cursor + 32)
        if (next > bounds.end || nameSize == 0 || nameSize > 4096) invalid()
        val nameBytes = bytes.copyOfRange(cursor + 46, extraStart)
        val fields = CentralEntry(cursor, nameBytes, decodeName(nameBytes, flags and 0x800 != 0)).apply {
            method = u16(cursor + 10)
            compressed = u32(cursor + 20)
            expanded = u32(cursor + 24)
            disk = u16(cursor + 34).toLong()
            offset = u32(cursor + 42)
        }
        readExtras(fields, extraStart, extraEnd)
        if (fields.disk != 0L) unsupported()
        val local = validateLocal(fields, bounds.start)
        val attributes = u32(cursor + 38)
        val mode = ((attributes ushr 16) and 0xf000).toInt()
        val directory = fields.name.endsWith('/') || attributes and 0x10L != 0L || mode == 0x4000
        val safe = safePath(fields.name, directory) && mode in listOf(0, 0x4000, 0x8000)
        val entry = ZipArchiveEntry(
            id = id,
            path = fields.name,
            isDirectory = directory,
            compressedSize = fields.compressed,
            size = fields.expanded,
            isEncrypted = flags and 1 != 0,
            isSupported = safe && supported(fields, flags),
            isSafe = safe,
            offset = local,
            crc = u32(cursor + 16),
        )
        return Triple(entry, nameBytes, next)
    }

    // AES entries (method 99) only pass once their extra field supplied the real method.
    private fun supported(fields: CentralEntry, flags: Int): Boolean =
        fields.method in listOf(0, 8) && flags and 0x2040 == 0

    private fun readExtras(fields: CentralEntry, start: Int, end: Int) {
        var extra = start
        while (extra < end) {
            if (extra > end - 4) invalid()
            val type = u16(extra)
            val length = u16(extra + 2)
            val data = extra + 4
            val extraEnd = data + length
            if (extraEnd > end) invalid()
            when (type) {
                1 -> readZip64Extra(fields, data, extraEnd)
                0x9901 -> readAesExtra(fields, data, length)
                0x7075 -> readUnicodeExtra(fields, data, length)
            }
            extra = extraEnd
        }
        val needsZip64 = fields.expanded == 0xffffffffL ||
            fields.compressed == 0xffffffffL || fields.offset == 0xffffffffL
        if (!fields.zip64 && needsZip64) invalid()
    }

    private fun readZip64Extra(fields: CentralEntry, start: Int, end: Int) {
        if (fields.zip64) invalid()
        fields.zip64 = true
        var p = start
        fun readLong(): Long {
            if (p > end - 8) invalid()
            return u64(p).also { p += 8 }
        }
        if (fields.expanded == 0xffffffffL) fields.expanded = readLong()
        if (fields.compressed == 0xffffffffL) fields.compressed = readLong()
        if (fields.offset == 0xffffffffL) fields.offset = readLong()
        if (fields.disk == 0xffffL) {
            if (p > end - 4) invalid()
            fields.disk = u32(p)
        }
    }

    private fun readAesExtra(fields: CentralEntry, data: Int, length: Int) {
        if (length != 7 || u16(data) !in 1..2) return
        if (u16(data + 2) != 0x4541 || u8(data + 4) !in 1..3) return
        if (fields.method == 99) fields.method = u16(data + 5)
    }

    private fun readUnicodeExtra(fields: CentralEntry, data: Int, length: Int) {
        if (length > 5 && u8(data) == 1 && u32(data + 1) == zipNameCrc(fields.nameBytes)) {
            fields.name = decodeName(bytes.copyOfRange(data + 5, data + length), true)
        }
    }

    private fun validateLocal(fields: CentralEntry, directoryStart: Int): Int {
        val local = index(fields.offset)
        if (local > directoryStart - 30 || u32(local) != 0x04034b50L) invalid()
        if (u16(local + 6) != u16(fields.position + 8) || u16(local + 8) != u16(fields.position + 10)) invalid()
        val localNameSize = u16(local + 26)
        val dataStart = local.toLong() + 30 + localNameSize + u16(local + 28)
        if (dataStart > directoryStart || fields.compressed > directoryStart - dataStart) invalid()
        if (localNameSize != fields.nameBytes.size) invalid()
        if (!bytes.copyOfRange(local + 30, local + 30 + localNameSize).contentEquals(fields.nameBytes)) invalid()
        return local
    }

    private fun index(value: Long): Int {
        if (value < 0 || value > bytes.size) invalid()
        return value.toInt()
    }
    private fun u8(p: Int): Int {
        if (p !in bytes.indices) invalid()
        return bytes[p].toInt() and 0xff
    }
    private fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
    private fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
    private fun u64(p: Int): Long {
        val high = u32(p + 4)
        if (high > Int.MAX_VALUE) invalid()
        return u32(p) or (high shl 32)
    }
}

private fun safePath(path: String, directory: Boolean): Boolean {
    val parts = path.removeSuffix("/").split('/')
    return path.none { it < ' ' || it == '\u007f' || it == '\\' || it == ':' } &&
        parts.all { it.isNotEmpty() && it != "." && it != ".." } &&
        (directory || !path.endsWith('/'))
}

private fun decodeName(bytes: ByteArray, utf8: Boolean): String = if (utf8) {
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw ZipArchiveException(ZipArchiveError.Invalid, e)
    }
} else {
    buildString(bytes.size) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(if (value < 128) value.toChar() else CP437[value - 128])
        }
    }
}

private fun zipNameCrc(bytes: ByteArray): Long {
    var crc = -1
    bytes.forEach { byte ->
        crc = crc xor (byte.toInt() and 0xff)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    return crc.inv().toLong() and 0xffffffffL
}

private fun invalid(): Nothing = throw ZipArchiveException(ZipArchiveError.Invalid)
private fun unsupported(): Nothing = throw ZipArchiveException(ZipArchiveError.Unsupported)
private const val CP437 =
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
    "áíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
    "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
    "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "
