package com.artemchep.keyguard.common.service.serialization

import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Application and persisted-data JSON, including legacy polymorphic fallbacks. */
fun createApplicationJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    prettyPrint = false
    isLenient = true
    serializersModule = SerializersModule {
        polymorphic(BitwardenCipher.Attachment::class) {
            subclass(BitwardenCipher.Attachment.Remote::class)
            subclass(BitwardenCipher.Attachment.Local::class)
            defaultDeserializer { BitwardenCipher.Attachment.Remote.serializer() }
        }
        polymorphic(ServiceToken::class) {
            subclass(BitwardenToken::class)
            subclass(KeePassToken::class)
            defaultDeserializer { BitwardenToken.serializer() }
        }
        polymorphic(PrivilegedAppListEntity.App::class) {
            subclass(PrivilegedAppListEntity.App.AndroidApp::class)
            subclass(PrivilegedAppListEntity.App.Unknown::class)
            defaultDeserializer { PrivilegedAppListEntity.App.Unknown.serializer() }
        }
    }
}
