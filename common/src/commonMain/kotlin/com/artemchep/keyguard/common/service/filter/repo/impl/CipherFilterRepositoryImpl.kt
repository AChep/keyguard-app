package com.artemchep.keyguard.common.service.filter.repo.impl

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.service.filter.entity.FilterEntity
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import com.artemchep.keyguard.common.service.state.impl.toJson
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.CipherFilter
import com.artemchep.keyguard.data.CipherFilterQueries
import com.artemchep.keyguard.feature.home.vault.screen.FilterSection
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CipherFilterRepositoryImpl(
    private val context: LeContext,
    private val databaseManager: DatabaseManager,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : CipherFilterRepository {
    companion object {
        private const val TYPE_LOGIN = "login"
        private const val TYPE_CARD = "card"
        private const val TYPE_IDENTITY = "identity"
        private const val TYPE_NOTE = "note"
        private const val TYPE_OTP = "otp"
    }

    private interface FilterEntityMapper {
        fun map(
            context: LeContext,
            entity: CipherFilter,
        ): DCipherFilter
    }

    private class BaseFilterEntityMapper(
        private val icon: ImageVector,
        private val name: StringResource,
        private val state: Map<String, Set<DFilter.Primitive>>,
    ) : FilterEntityMapper {
        override fun map(
            context: LeContext,
            entity: CipherFilter,
        ): DCipherFilter {
            val name = entity.name.takeIf { it.isNotBlank() }
                ?: textResource(
                    res = name,
                    context = context,
                )
            return DCipherFilter(
                idRaw = entity.id,
                icon = icon,
                name = name,
                filter = state,
                updatedDate = entity.updatedAt,
                createdDate = entity.createdAt,
            )
        }
    }

    private val filterEntityMappers = mapOf(
        TYPE_LOGIN to BaseFilterEntityMapper(
            icon = DSecret.Type.Login.iconImageVector(),
            name = Res.strings.cipher_type_login,
            state = mapOf(
                FilterSection.TYPE.id to setOf(
                    DFilter.ByType(DSecret.Type.Login),
                ),
            ),
        ),
        TYPE_CARD to BaseFilterEntityMapper(
            icon = DSecret.Type.Card.iconImageVector(),
            name = Res.strings.cipher_type_card,
            state = mapOf(
                FilterSection.TYPE.id to setOf(
                    DFilter.ByType(DSecret.Type.Card),
                ),
            ),
        ),
        TYPE_IDENTITY to BaseFilterEntityMapper(
            icon = DSecret.Type.Identity.iconImageVector(),
            name = Res.strings.cipher_type_identity,
            state = mapOf(
                FilterSection.TYPE.id to setOf(
                    DFilter.ByType(DSecret.Type.Identity),
                ),
            ),
        ),
        TYPE_NOTE to BaseFilterEntityMapper(
            icon = DSecret.Type.SecureNote.iconImageVector(),
            name = Res.strings.cipher_type_note,
            state = mapOf(
                FilterSection.TYPE.id to setOf(
                    DFilter.ByType(DSecret.Type.SecureNote),
                ),
            ),
        ),
        TYPE_OTP to BaseFilterEntityMapper(
            icon = Icons.Outlined.KeyguardTwoFa,
            name = Res.strings.one_time_password,
            state = mapOf(
                FilterSection.MISC.id to setOf(
                    DFilter.ByOtp,
                ),
            ),
        ),
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
        databaseManager = directDI.instance(),
        json = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DCipherFilter>> =
        daoEffect { dao ->
            dao.get()
        }
            .flatMapQueryToList(dispatcher)
            .map { entities ->
                entities
                    .mapNotNull { entity ->
                        if (entity.type != null) {
                            val mapper = filterEntityMappers[entity.type]
                            // Unknown type, skip the entity.
                                ?: return@mapNotNull null
                            return@mapNotNull mapper.map(
                                context = context,
                                entity = entity,
                            )
                        }

                        val data = kotlin
                            .runCatching {
                                val el = json.decodeFromString<FilterEntity>(entity.data_)
                                el
                            }
                            .getOrElse {
                                FilterEntity(
                                    state = emptyMap(),
                                )
                            }
                        DCipherFilter(
                            idRaw = entity.id,
                            icon = null,
                            name = entity.name,
                            filter = data.state,
                            updatedDate = entity.updatedAt,
                            createdDate = entity.createdAt,
                        )
                    }
                    .sorted()
            }

    override fun post(
        data: AddCipherFilterRequest,
    ): IO<Unit> = daoEffect { dao ->
        val data2 = kotlin.run {
            val el = FilterEntity(
                state = data.filter,
            )
            json.encodeToString(el)
        }
        dao.insert(
            name = data.name,
            data = data2,
            updatedAt = data.now,
            createdAt = data.now,
            icon = null,
        )
    }

    override fun patch(
        id: Long,
        name: String,
    ): IO<Unit> = daoEffect { dao ->
        dao.rename(
            id = id,
            name = name,
        )
    }

    override fun removeAll(): IO<Unit> =
        daoEffect { dao ->
            dao.deleteAll()
        }

    override fun removeByIds(ids: Set<Long>): IO<Unit> =
        daoEffect { dao ->
            dao.transaction {
                ids.forEach { id ->
                    dao.deleteByIds(id)
                }
            }
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (CipherFilterQueries) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap(dispatcher) { db ->
            val dao = db.cipherFilterQueries
            block(dao)
        }
}
