package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.GeneratorHistoryQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class KeyPairEntity(
    @SerialName("type")
    val type: Type,
    @SerialName("privateKey")
    val privateKey: KeyParameter,
    @SerialName("publicKey")
    val publicKey: KeyParameter,
) {
    companion object {
        context(Base64Service)
        fun of(
            model: KeyParameterRawZero,
        ): KeyPairEntity {
            val type = of(model.type)
            val privateKey = of(model.privateKey)
            val publicKey = of(model.publicKey)
            return KeyPairEntity(
                type = type,
                privateKey = privateKey,
                publicKey = publicKey,
            )
        }

        private fun of(
            model: KeyPair.Type,
        ): Type = when (model) {
            KeyPair.Type.RSA -> Type.RSA
            KeyPair.Type.ED25519 -> Type.ED25519
        }

        context(Base64Service)
        private fun of(
            model: KeyParameterRawZero.KeyParameterRaw,
        ): KeyParameter {
            val encodedBase64 = encodeToString(model.encoded)
            return KeyParameter(
                encodedBase64 = encodedBase64,
            )
        }
    }

    @Serializable
    enum class Type {
        @SerialName("rsa")
        RSA,

        @SerialName("ed25519")
        ED25519,
    }

    @Serializable
    data class KeyParameter(
        @SerialName("encodedBase64")
        val encodedBase64: String,
    )
}

context(Base64Service)
fun KeyPairEntity.toDomain(): KeyParameterRawZero {
    val type = type.toDomain()
    val privateKey = privateKey.toDomain()
    val publicKey = publicKey.toDomain()
    return KeyPairRaw(
        type = type,
        privateKey = privateKey,
        publicKey = publicKey,
    )
}

private fun KeyPairEntity.Type.toDomain() = when (this) {
    KeyPairEntity.Type.RSA -> KeyPair.Type.RSA
    KeyPairEntity.Type.ED25519 -> KeyPair.Type.ED25519
}

context(Base64Service)
private fun KeyPairEntity.KeyParameter.toDomain(
): KeyPairRaw.KeyParameter {
    val encoded = decode(encodedBase64)
    return KeyPairRaw.KeyParameter(
        encoded = encoded,
    )
}

class GeneratorHistoryRepositoryImpl(
    private val databaseManager: DatabaseManager,
    private val base64Service: Base64Service,
    private val keyPairGenerator: KeyPairGenerator,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : GeneratorHistoryRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
        base64Service = directDI.instance(),
        keyPairGenerator = directDI.instance(),
        json = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DGeneratorHistory>> =
        daoEffect { dao ->
            dao.get(1000)
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .map { entity ->
                        val value = if (entity.isSshKey == true) {
                            val valueRawEntity = json.decodeFromString<KeyPairEntity>(entity.value_)
                            val valueRaw = with(base64Service) {
                                valueRawEntity.toDomain()
                            }

                            val keyPair = keyPairGenerator.populate(valueRaw)
                            GetPasswordResult.AsyncKey(
                                keyPair = keyPair,
                            )
                        } else {
                            GetPasswordResult.Value(entity.value_)
                        }
                        DGeneratorHistory(
                            id = entity.id.toString(),
                            value = value,
                            createdDate = entity.createdAt,
                            isPassword = entity.isPassword,
                            isUsername = entity.isUsername,
                            isEmailRelay = entity.isEmailRelay == true,
                            isSshKey = entity.isSshKey == true,
                        )
                    }
            }

    override fun put(model: DGeneratorHistory): IO<Unit> =
        daoEffect { dao ->
            val value = if (model.isSshKey) {
                require(model.value is GetPasswordResult.AsyncKey) {
                    "SSH key generator history item must have a key pair value!"
                }

                // Encode the key pair as JSON and save to the
                // value field. Later we will have to decode it.
                val valueEntity = with(base64Service) {
                    KeyPairEntity.of(model.value.keyPair)
                }
                json.encodeToString(valueEntity)
            } else {
                require(model.value is GetPasswordResult.Value)
                model.value.value
            }
            dao.insert(
                value_ = value,
                createdAt = model.createdDate,
                isPassword = model.isPassword,
                isUsername = model.isUsername,
                isEmailRelay = model.isEmailRelay,
                isSshKey = model.isSshKey,
            )
        }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<String>): IO<Unit> =
        daoEffect { dao ->
            dao.transaction {
                ids.forEach {
                    val id = it.toLongOrNull()
                        ?: return@forEach
                    dao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (GeneratorHistoryQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.generatorHistoryQueries
            block(dao)
        }
}
