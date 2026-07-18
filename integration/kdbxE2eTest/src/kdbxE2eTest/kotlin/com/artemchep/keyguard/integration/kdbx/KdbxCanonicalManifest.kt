package com.artemchep.keyguard.integration.kdbx

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString
import java.util.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun KeePassDatabase.toCanonicalManifest(): JsonObject {
    val binaryKeys = binaries.keys.toList()
    return buildJsonObject {
        put("schemaVersion", 1)
        put("format", canonicalFormat())
        put("meta", content.meta.canonical())
        put("root", content.group.canonical(binaryKeys))
        put(
            "deletedObjects",
            buildJsonArray {
                content.deletedObjects.forEach { item ->
                    add(
                        buildJsonObject {
                            put("uuid", item.id.toString())
                            put("deletionTime", item.deletionTime.toString())
                        },
                    )
                }
            },
        )
        put(
            "binaries",
            buildJsonArray {
                binaries.values.forEach { binary ->
                    add(
                        buildJsonObject {
                            put("contentBase64", binary.getContent().base64())
                            put(
                                "storage",
                                if (binary is BinaryData.Compressed) "compressed" else "plain",
                            )
                            put("protected", binary.memoryProtection)
                        },
                    )
                }
            },
        )
        put("documentExtensions", content.documentExtensions.canonical())
        put("rootExtensions", content.rootExtensions.canonical())
    }
}

private fun KeePassDatabase.canonicalFormat() = buildJsonObject {
    put("version", "${header.version.major}.${header.version.minor}")
    put(
        "cipher",
        when (header.cipherId) {
            BaseCiphers.Aes.uuid -> "aes256"
            BaseCiphers.ChaCha20.uuid -> "chacha20"
            TwofishCipher.uuid -> "twofish"
            else -> header.cipherId.toString()
        },
    )
    put(
        "kdf",
        when (val value = header) {
            is DatabaseHeader.Ver3x -> "aeskdf"
            is DatabaseHeader.Ver4x -> when (val kdf = value.kdfParameters) {
                is KdfParameters.Aes -> "aeskdf"
                is KdfParameters.Argon2 -> when (kdf.variant) {
                    KdfParameters.Argon2.Variant.Argon2d -> "argon2d"
                    KdfParameters.Argon2.Variant.Argon2id -> "argon2id"
                }
            }
        },
    )
    put(
        "compression",
        when (header.compression) {
            DatabaseHeader.Compression.None -> "none"
            DatabaseHeader.Compression.GZip -> "gzip"
        },
    )
}

private fun Meta.canonical() = buildJsonObject {
    put("generator", generator)
    putNullable("settingsChanged", settingsChanged)
    put("name", name)
    putNullable("nameChanged", nameChanged)
    put("description", description)
    putNullable("descriptionChanged", descriptionChanged)
    put("defaultUser", defaultUser)
    putNullable("defaultUserChanged", defaultUserChanged)
    put("maintenanceHistoryDays", maintenanceHistoryDays.toLong())
    putNullable("color", color)
    putNullable("masterKeyChanged", masterKeyChanged)
    put("masterKeyChangeRec", masterKeyChangeRec)
    put("masterKeyChangeForce", masterKeyChangeForce)
    put("recycleBinEnabled", recycleBinEnabled)
    putNullable("recycleBinUuid", recycleBinUuid)
    putNullable("recycleBinChanged", recycleBinChanged)
    putNullable("entryTemplatesGroup", entryTemplatesGroup)
    putNullable("entryTemplatesGroupChanged", entryTemplatesGroupChanged)
    put("historyMaxItems", historyMaxItems)
    put("historyMaxSize", historyMaxSize)
    putNullable("lastSelectedGroup", lastSelectedGroup)
    putNullable("lastTopVisibleGroup", lastTopVisibleGroup)
    put(
        "memoryProtection",
        JsonArray(memoryProtection.map { JsonPrimitive(it.name) }),
    )
    put(
        "customIcons",
        buildJsonArray {
            customIcons.forEach { (uuid, icon) ->
                add(
                    buildJsonObject {
                        put("uuid", uuid.toString())
                        put("dataBase64", icon.data.base64())
                        putNullable("name", icon.name)
                        putNullable("lastModified", icon.lastModified)
                    },
                )
            }
        },
    )
    put("customData", customData.canonical())
    put("extensions", extensions.canonical())
}

private fun Group.canonical(binaryKeys: List<ByteString>): JsonObject = buildJsonObject {
    put("uuid", uuid.toString())
    put("name", name)
    put("notes", notes)
    put("icon", icon.ordinal)
    putNullable("customIconUuid", customIconUuid)
    putNullable("times", times?.canonical())
    put("expanded", expanded)
    putNullable("defaultAutoTypeSequence", defaultAutoTypeSequence)
    put("enableAutoType", enableAutoType.name)
    put("enableSearching", enableSearching.name)
    putNullable("lastTopVisibleEntry", lastTopVisibleEntry)
    putNullable("previousParentGroup", previousParentGroup)
    put("tags", JsonArray(tags.map(::JsonPrimitive)))
    put("customData", customData.canonical())
    put(
        "groups",
        buildJsonArray {
            groups.forEach { add(it.canonical(binaryKeys)) }
        },
    )
    put(
        "entries",
        buildJsonArray {
            entries.forEach { add(it.canonical(binaryKeys)) }
        },
    )
    put("extensions", extensions.canonical())
}

private fun Entry.canonical(binaryKeys: List<ByteString>): JsonObject = buildJsonObject {
    put("uuid", uuid.toString())
    put("icon", icon.ordinal)
    putNullable("customIconUuid", customIconUuid)
    putNullable("foregroundColor", foregroundColor)
    putNullable("backgroundColor", backgroundColor)
    put("overrideUrl", overrideUrl)
    putNullable("times", times?.canonical())
    putNullable("autoType", autoType?.canonical())
    put(
        "fields",
        buildJsonArray {
            fields.forEach { (key, value) ->
                add(
                    buildJsonObject {
                        put("key", key)
                        put("value", value.content)
                        put("protected", value is EntryValue.Encrypted)
                    },
                )
            }
        },
    )
    put("tags", JsonArray(tags.map(::JsonPrimitive)))
    put(
        "binaries",
        buildJsonArray {
            binaries.forEach { binary ->
                val index = binaryKeys.indexOf(binary.hash)
                check(index >= 0) { "Entry '$uuid' references a missing binary ${binary.hash.hex()}" }
                add(
                    buildJsonObject {
                        put("name", binary.name)
                        put("binaryIndex", index)
                    },
                )
            }
        },
    )
    put(
        "history",
        buildJsonArray {
            history.forEach { add(it.canonical(binaryKeys)) }
        },
    )
    put("customData", customData.canonical())
    putNullable("previousParentGroup", previousParentGroup)
    put("qualityCheck", qualityCheck)
    put("extensions", extensions.canonical())
}

private fun TimeData.canonical() = buildJsonObject {
    putNullable("creationTime", creationTime)
    putNullable("lastAccessTime", lastAccessTime)
    putNullable("lastModificationTime", lastModificationTime)
    putNullable("locationChanged", locationChanged)
    putNullable("expiryTime", expiryTime)
    put("expires", expires)
    put("usageCount", usageCount)
}

private fun AutoTypeData.canonical() = buildJsonObject {
    put("enabled", enabled)
    put("obfuscation", obfuscation.ordinal)
    putNullable("defaultSequence", defaultSequence)
    put(
        "associations",
        buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("window", item.window)
                        put("sequence", item.keystrokeSequence)
                    },
                )
            }
        },
    )
}

private fun Map<String, CustomDataValue>.canonical() = buildJsonArray {
    this@canonical.forEach { (key, item) ->
        add(
            buildJsonObject {
                put("key", key)
                put("value", item.value)
                putNullable("lastModified", item.lastModified)
            },
        )
    }
}

private fun List<XmlExtension>.canonical() = buildJsonArray {
    this@canonical.forEach { add(it.canonical()) }
}

private fun XmlExtension.canonical(): JsonObject = buildJsonObject {
    put(
        "name",
        buildJsonObject {
            put("localName", name.localName)
            put("namespaceUri", name.namespaceUri)
        },
    )
    put(
        "attributes",
        buildJsonArray {
            attributes.forEach { attribute ->
                add(
                    buildJsonObject {
                        put(
                            "name",
                            buildJsonObject {
                                put("localName", attribute.name.localName)
                                put("namespaceUri", attribute.name.namespaceUri)
                            },
                        )
                        put("value", attribute.value)
                    },
                )
            }
        },
    )
    put(
        "content",
        buildJsonArray {
            content.forEach { item ->
                add(
                    when (item) {
                        is XmlExtensionContent.Text -> buildJsonObject {
                            put("kind", "text")
                            put("value", item.value.content)
                            put("protected", item.value is EntryValue.Encrypted)
                        }

                        is XmlExtensionContent.Element -> buildJsonObject {
                            put("kind", "element")
                            put("value", item.value.canonical())
                        }

                        is XmlExtensionContent.Comment -> buildJsonObject {
                            put("kind", "comment")
                            put("value", item.value)
                        }

                        is XmlExtensionContent.ProcessingInstruction -> buildJsonObject {
                            put("kind", "processingInstruction")
                            put("target", item.target)
                            put("data", item.data)
                        }
                    },
                )
            }
        },
    )
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: JsonElement?,
) {
    put(key, value ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Instant?,
) {
    putNullable(key, value?.toString())
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Uuid?,
) {
    putNullable(key, value?.toString())
}
