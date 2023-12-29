package com.artemchep.keyguard.common.model

import arrow.core.Either
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kodein.di.DirectDI

@Serializable
sealed interface DSendFilter {
    companion object {
        inline fun <reified T> findOne(
            filter: DSendFilter,
            noinline predicate: (T) -> Boolean = { true },
        ): T? = findOne(
            filter = filter,
            target = T::class.java,
            predicate = predicate,
        )

        fun <T> findOne(
            filter: DSendFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): T? = _findOne(
            filter = filter,
            target = target,
            predicate = predicate,
        ).getOrNull()

        private fun <T> _findOne(
            filter: DSendFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): Either<Unit, T?> = when (filter) {
            is Or<*> -> {
                when (filter.filters.size) {
                    0 -> Either.Right(null)
                    1 -> {
                        val f = filter.filters.first()
                        _findOne(f, target, predicate)
                    }

                    else -> Either.Left(Unit)
                }
            }

            is And<*> -> {
                val results = filter
                    .filters
                    .map { f ->
                        _findOne(f, target)
                    }
                // If any of the variants has returned that
                // we must abort the search then abort the search.
                if (results.any { it.isLeft() }) {
                    // Propagate the error down the pipe.
                    Either.Left(Unit)
                } else {
                    val fs = results.mapNotNull { it.getOrNull() }
                    when (fs.size) {
                        0 -> Either.Right(null)
                        1 -> Either.Right(fs.first())
                        else -> Either.Left(Unit)
                    }
                }
            }

            else -> {
                if (filter.javaClass == target) {
                    val f = filter as T
                    val v = predicate(f)
                    if (v) {
                        Either.Right(f)
                    } else {
                        Either.Right(null)
                    }
                } else {
                    Either.Right(null)
                }
            }
        }
    }

    suspend fun prepare(
        directDI: DirectDI,
        ciphers: List<DSend>,
    ): (DSend) -> Boolean

    @Serializable
    sealed interface Primitive : DSendFilter {
        val key: String
    }

    @Serializable
    @SerialName("or")
    data class Or<out T : DSendFilter>(
        val filters: Collection<T>,
    ) : DSendFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSend>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepare(directDI, ciphers) }
            return@run { cipher: DSend ->
                list.isEmpty() || list.any { predicate -> predicate(cipher) }
            }
        }
    }

    @Serializable
    @SerialName("and")
    data class And<out T : DSendFilter>(
        val filters: Collection<T>,
    ) : DSendFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSend>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepare(directDI, ciphers) }
            return@run { cipher: DSend ->
                list.isEmpty() || list.all { predicate -> predicate(cipher) }
            }
        }
    }

    @Serializable
    @SerialName("all")
    data object All : DSendFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSend>,
        ) = ::predicateCipher

        private fun predicateCipher(cipher: DSend) = true
    }

    // Primitives

    @Serializable
    @SerialName("by_id")
    data class ById(
        val id: String?,
        val what: What,
    ) : Primitive {
        override val key: String = "$id|$what"

        @Serializable
        enum class What {
            @SerialName("account")
            ACCOUNT,
        }

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSend>,
        ) = ::predicateCipher

        private fun predicateCipher(
            cipher: DSend,
        ) = kotlin.run {
            when (what) {
                What.ACCOUNT -> cipher.accountId
            } == id
        }
    }

    @Serializable
    @SerialName("by_type")
    data class ByType(
        val type: DSend.Type,
    ) : Primitive {
        override val key: String = "$type"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSend>,
        ) = ::predicate

        private fun predicate(
            cipher: DSend,
        ) = cipher.type == type
    }
}
