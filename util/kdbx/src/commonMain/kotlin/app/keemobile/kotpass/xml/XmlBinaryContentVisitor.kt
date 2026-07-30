package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.LimitedSource
import app.keemobile.kotpass.io.MAX_DECOMPRESSED_SIZE
import app.keemobile.kotpass.io.gunzipSource
import nl.adaptivity.xmlutil.XmlReader
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import kotlin.coroutines.cancellation.CancellationException

internal fun interface XmlBinaryContentVisitor {
    /**
     * [source] contains the uncompressed attachment bytes and is valid only
     * until this callback returns. [declaredLength] is always `null` here:
     * XML-embedded binaries are base64 text whose decoded size is unknown
     * until the stream ends.
     */
    fun visit(source: Source, declaredLength: Long?)
}

// The generic catch converts any parser failure into a FormatError; known
// error types pass through untouched.
@Suppress("TooGenericExceptionCaught")
internal fun visitXmlBinaryContents(
    source: BufferedSource,
    innerEncryption: EncryptionSaltGenerator,
    visitor: XmlBinaryContentVisitor,
    limits: XmlReadLimits = XmlReadLimits.Default,
    checkCancellation: () -> Unit = {},
): ByteString? {
    var headerHash: ByteString? = null
    try {
        val reader = xmlReader(source, limits)
        reader.enterKeePassDocumentRoot()
        reader.scanBinaryElement(
            innerEncryption = innerEncryption,
            visitor = visitor,
            parentName = null,
            grandparentName = null,
            entryBinaryContainer = false,
            onHeaderHash = { value -> headerHash = value },
            checkCancellation = checkCancellation,
        )
        reader.finishDocument()
    } catch (e: Exception) {
        throw e.toBinaryVisitError()
    }
    return headerHash
}

private fun XmlReader.enterKeePassDocumentRoot() {
    enterDocumentRoot()
        ?: throw FormatError.InvalidXml("No root found.")
    if (!isUnqualifiedElement(FormatXml.Tags.Document)) {
        throw FormatError.InvalidXml("Unexpected document root '$localName'.")
    }
}

private fun Exception.toBinaryVisitError(): Throwable = when (this) {
    is FormatError, is CancellationException -> this
    else -> FormatError.InvalidXml(
        message ?: "Malformed XML document.",
        this,
    )
}

private enum class XmlBinaryKind {
    /** `Meta/Binaries/Binary` pool item (KDBX 3). */
    Pooled,

    /** `Entry/Binary/Value` without a `Ref` attribute. */
    InlineValue,
}

private fun XmlReader.scanBinaryElement(
    innerEncryption: EncryptionSaltGenerator,
    visitor: XmlBinaryContentVisitor,
    parentName: String?,
    grandparentName: String?,
    entryBinaryContainer: Boolean,
    onHeaderHash: (ByteString?) -> Unit,
    checkCancellation: () -> Unit,
) {
    checkCancellation()
    val currentName = localName
    val binaryKind = binaryContentKind(parentName, grandparentName, entryBinaryContainer)
    when {
        binaryKind != null -> visitBinaryElement(
            kind = binaryKind,
            innerEncryption = innerEncryption,
            visitor = visitor,
            checkCancellation = checkCancellation,
        )

        isHeaderHashElement(parentName) -> readHeaderHash(onHeaderHash, checkCancellation)

        else -> {
            val markers = readProtectedXmlValueMarkers()
            if (markers.isProtected) {
                discardProtectedTextStreaming(
                    markers = markers,
                    innerEncryption = innerEncryption,
                    checkCancellation = checkCancellation,
                )
            } else {
                val childIsEntryBinary = isEntryBinaryContainer(parentName)
                forEachChildElement {
                    scanBinaryElement(
                        innerEncryption = innerEncryption,
                        visitor = visitor,
                        parentName = currentName,
                        grandparentName = parentName,
                        entryBinaryContainer = childIsEntryBinary,
                        onHeaderHash = onHeaderHash,
                        checkCancellation = checkCancellation,
                    )
                }
            }
        }
    }
}

private fun XmlReader.binaryContentKind(
    parentName: String?,
    grandparentName: String?,
    entryBinaryContainer: Boolean,
): XmlBinaryKind? {
    if (namespaceURI.isNotEmpty()) return null
    val isPooledBinary =
        localName == FormatXml.Tags.Meta.Binaries.Item &&
            parentName == FormatXml.Tags.Meta.Binaries.TagName &&
            grandparentName == FormatXml.Tags.Meta.TagName
    val isInlineBinaryValue =
        localName == FormatXml.Tags.Entry.BinaryReferences.ItemValue &&
            entryBinaryContainer &&
            attributeOrNull(FormatXml.Attributes.Ref) == null
    return when {
        isPooledBinary -> XmlBinaryKind.Pooled
        isInlineBinaryValue -> XmlBinaryKind.InlineValue
        else -> null
    }
}

private fun XmlReader.visitBinaryElement(
    kind: XmlBinaryKind,
    innerEncryption: EncryptionSaltGenerator,
    visitor: XmlBinaryContentVisitor,
    checkCancellation: () -> Unit,
) {
    val compressed = booleanAttributeOrNull(FormatXml.Attributes.Compressed) ?: false
    val markers = when (kind) {
        XmlBinaryKind.InlineValue -> readProtectedXmlValueMarkers()
        XmlBinaryKind.Pooled -> XmlProtectedValueMarkers(
            usesInnerEncryption = false,
            protectsInMemory = false,
        )
    }
    visitBinaryText(
        innerEncryption = innerEncryption,
        markers = markers,
        compressed = compressed,
        visitor = visitor,
        checkCancellation = checkCancellation,
    )
}

private fun XmlReader.isHeaderHashElement(parentName: String?): Boolean =
    namespaceURI.isEmpty() &&
        localName == FormatXml.Tags.Meta.HeaderHash &&
        parentName == FormatXml.Tags.Meta.TagName

private fun XmlReader.readHeaderHash(
    onHeaderHash: (ByteString?) -> Unit,
    checkCancellation: () -> Unit,
) {
    val base64 = XmlBase64Source(this, checkCancellation)
    try {
        val data = base64.readBoundedByteArray(maximumBytes = 32)
        onHeaderHash(data?.toByteString())
    } finally {
        base64.close()
    }
}

private fun XmlReader.isEntryBinaryContainer(parentName: String?): Boolean =
    namespaceURI.isEmpty() &&
        localName == FormatXml.Tags.Entry.BinaryReferences.TagName &&
        parentName == FormatXml.Tags.Entry.TagName

private fun XmlReader.visitBinaryText(
    innerEncryption: EncryptionSaltGenerator,
    markers: XmlProtectedValueMarkers,
    compressed: Boolean,
    visitor: XmlBinaryContentVisitor,
    checkCancellation: () -> Unit,
) {
    var content: Source = XmlBase64Source(this, checkCancellation)
    if (markers.usesInnerEncryption) {
        content = InnerEncryptionSource(
            delegate = content,
            innerEncryption = innerEncryption,
        )
    }
    if (compressed) {
        content = content.gunzipSource(MAX_DECOMPRESSED_SIZE)
    } else {
        content = LimitedSource(
            delegate = content,
            maximumBytes = MAX_DECOMPRESSED_SIZE,
            limitExceeded = {
                FormatError.InvalidContent(
                    "Binary content exceeds $MAX_DECOMPRESSED_SIZE bytes.",
                )
            },
        )
    }
    try {
        visitor.visit(content, null)
        content.drain(checkCancellation)
    } finally {
        content.close()
    }
}
