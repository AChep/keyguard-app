package com.artemchep.keyguard.room

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@ProvidedTypeConverter
class RoomSecretConverter(
    private val json: Json,
) {
    @TypeConverter
    fun encodeToken(model: BitwardenToken?) = encode(model)

    @TypeConverter
    fun encodeCipher(model: BitwardenCipher?) = encode(model)

    @TypeConverter
    fun encodeCollection(model: BitwardenCollection?) = encode(model)

    @TypeConverter
    fun encodeFolder(model: BitwardenFolder?) = encode(model)

    @TypeConverter
    fun encodeMeta(model: BitwardenMeta?) = encode(model)

    @TypeConverter
    fun encodeProfile(model: BitwardenProfile?) = encode(model)

    @TypeConverter
    fun encodeOrganization(model: BitwardenOrganization?) = encode(model)

    private inline fun <reified T : Any> encode(model: T?): String? {
        if (model == null) {
            // We can not serialize null models, so just
            // write them as null.
            return null
        }

        return json.encodeToString(model)
    }

    @TypeConverter
    fun decodeToken(text: String?): BitwardenToken? = decode(text)

    @TypeConverter
    fun decodeCipher(text: String?): BitwardenCipher? = decode(text)

    @TypeConverter
    fun decodeCollection(text: String?): BitwardenCollection? = decode(text)

    @TypeConverter
    fun decodeFolder(text: String?): BitwardenFolder? = decode(text)

    @TypeConverter
    fun decodeMeta(text: String?): BitwardenMeta? = decode(text)

    @TypeConverter
    fun decodeProfile(text: String?): BitwardenProfile? = decode(text)

    @TypeConverter
    fun decodeOrganization(text: String?): BitwardenOrganization? = decode(text)

    private inline fun <reified T : Any> decode(text: String?): T? {
        if (text == null) {
            // We can not serialize null models, so just
            // write them as null.
            return null
        }

        return json.decodeFromString<T>(text)
    }
}
