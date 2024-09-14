package com.artemchep.keyguard.common.service.googleauthenticator.util

import arrow.core.Either
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData
import com.artemchep.keyguard.common.service.text.Base64Service
import io.ktor.http.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.kodein.di.DirectDI
import org.kodein.di.instance

class OtpMigrationParser(
    private val base64Service: Base64Service,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        base64Service = directDI.instance(),
    )

    fun parse(
        uri: String,
    ): Either<Throwable, OtpAuthMigrationData> = Either.catch {
        parseData(uri)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseData(
        uri: String,
    ): OtpAuthMigrationData {
        val protoDataBase64 = Url(uri)
            .parameters["data"]
        requireNotNull(protoDataBase64) {
            "URI must have the data parameter!"
        }
        val protoData = base64Service.decode(protoDataBase64)
        return ProtoBuf.decodeFromByteArray<OtpAuthMigrationData>(protoData)
    }
}
