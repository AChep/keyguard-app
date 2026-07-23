package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.errors.KeyfileError
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.io.encodeHex
import app.keemobile.kotpass.xml.KeyfileXml
import app.keemobile.kotpass.xml.attribute
import app.keemobile.kotpass.xml.attributeOrNull
import app.keemobile.kotpass.xml.buildXmlString
import app.keemobile.kotpass.xml.element
import app.keemobile.kotpass.xml.enterDocumentRoot
import app.keemobile.kotpass.xml.forEachChildElement
import app.keemobile.kotpass.xml.isUnqualifiedElement
import app.keemobile.kotpass.xml.readElementTextOrNull
import app.keemobile.kotpass.xml.skipElement
import app.keemobile.kotpass.xml.xmlReader
import com.artemchep.keyguard.util.foundation.crypto.sha256

private const val DefaultVersion = "2.0"

private val SpacesPattern = Regex("\\s+")

class Credentials private constructor(
    val passphrase: EncryptedValue?,
    val key: EncryptedValue?
) {
    companion object {
        fun from(passphrase: EncryptedValue) = Credentials(
            passphrase = EncryptedValue.fromBinary(passphrase.getHash()),
            key = null
        )

        fun from(keyData: ByteArray) = Credentials(
            passphrase = null,
            key = EncryptedValue.fromBinary(parseKeyfile(keyData))
        )

        fun from(passphrase: EncryptedValue, keyData: ByteArray) = Credentials(
            passphrase = EncryptedValue.fromBinary(passphrase.getHash()),
            key = EncryptedValue.fromBinary(parseKeyfile(keyData))
        )

        fun createKeyfile(key: ByteArray): String {
            val hash = sha256(key)
                .sliceArray(0 until 4)
                .encodeHex()
                .uppercase()

            return buildXmlString(KeyfileXml.Tags.Document, pretty = true) {
                element(KeyfileXml.Tags.Meta) {
                    element(KeyfileXml.Tags.Version) {
                        text(DefaultVersion)
                    }
                }
                element(KeyfileXml.Tags.Key) {
                    element(KeyfileXml.Tags.Data) {
                        attribute(KeyfileXml.Attributes.Hash, hash)
                        text(key.encodeHex().uppercase())
                    }
                }
            }
        }

        private fun parseKeyfile(keyData: ByteArray): ByteArray {
            if (keyData.size == 32) {
                return keyData.copyOf()
            }
            if (keyData.size == 64 && keyData.all(::isAsciiHexDigit)) {
                return keyData
                    .decodeToString()
                    .decodeHexToArray()
            }

            return parseXmlKeyfile(keyData)
                ?.let(::findXmlKeyData)
                ?: sha256(keyData) // Use raw binary data as keyfile
        }

        private fun isAsciiHexDigit(value: Byte): Boolean = when (value.toInt()) {
            in '0'.code..'9'.code,
            in 'A'.code..'F'.code,
            in 'a'.code..'f'.code -> true
            else -> false
        }

        private fun parseXmlKeyfile(keyData: ByteArray): XmlKeyfile? = try {
            val reader = xmlReader(keyData)
            if (
                reader.enterDocumentRoot() == null ||
                !reader.isUnqualifiedElement(KeyfileXml.Tags.Document)
            ) {
                null
            } else {
                var version: Float? = null
                var hasKeyData = false
                var hash: String? = null
                var data: String? = null

                reader.forEachChildElement {
                    when {
                        reader.isUnqualifiedElement(KeyfileXml.Tags.Meta) ->
                            reader.forEachChildElement {
                                if (reader.isUnqualifiedElement(KeyfileXml.Tags.Version)) {
                                    version = reader.readElementTextOrNull()
                                        ?.trim()
                                        ?.toFloatOrNull()
                                } else {
                                    reader.skipElement()
                                }
                            }
                        reader.isUnqualifiedElement(KeyfileXml.Tags.Key) ->
                            reader.forEachChildElement {
                                if (reader.isUnqualifiedElement(KeyfileXml.Tags.Data)) {
                                    hasKeyData = true
                                    hash = reader.attributeOrNull(KeyfileXml.Attributes.Hash)
                                    data = reader.readElementTextOrNull()?.trim()
                                } else {
                                    reader.skipElement()
                                }
                            }
                        else -> reader.skipElement()
                    }
                }
                XmlKeyfile(version, hasKeyData, hash, data)
            }
        } catch (_: Exception) {
            null
        }

        private fun findXmlKeyData(keyfile: XmlKeyfile): ByteArray {
            val version = keyfile.version
                ?: throw KeyfileError.InvalidVersion()
            if (!keyfile.hasKeyData) {
                throw KeyfileError.NoKeyData()
            }

            return when (version) {
                1.0f -> {
                    keyfile.data
                        ?.decodeBase64ToArray()
                        ?: throw KeyfileError.NoKeyData()
                }
                2.0f -> {
                    val hash = keyfile.hash
                        ?.decodeHexToArray()
                        ?: throw KeyfileError.InvalidHash()
                    keyfile.data
                        ?.replace(SpacesPattern, "")
                        ?.decodeHexToArray()
                        ?.also { data ->
                            if (!sha256(data).sliceArray(0 until 4).contentEquals(hash)) {
                                throw KeyfileError.InvalidHash()
                            }
                        }
                        ?: throw KeyfileError.NoKeyData()
                }
                else -> throw KeyfileError.InvalidVersion()
            }
        }
    }

    private class XmlKeyfile(
        val version: Float?,
        val hasKeyData: Boolean,
        val hash: String?,
        val data: String?
    )
}
