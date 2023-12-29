package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(UriMatchTypeEntitySerializer::class)
enum class UriMatchTypeEntity(
    override val int: Int,
) : IntEnum {
    Domain(0),
    Host(1),
    StartsWith(2),
    Exact(3),
    RegularExpression(4),
    Never(5),
    ;

    companion object
}

object UriMatchTypeEntitySerializer :
    CommonEnumIntSerializer<UriMatchTypeEntity>(UriMatchTypeEntity::class)

fun UriMatchTypeEntity.Companion.of(
    model: BitwardenCipher.Login.Uri.MatchType,
) = when (model) {
    BitwardenCipher.Login.Uri.MatchType.Domain -> UriMatchTypeEntity.Domain
    BitwardenCipher.Login.Uri.MatchType.Host -> UriMatchTypeEntity.Host
    BitwardenCipher.Login.Uri.MatchType.StartsWith -> UriMatchTypeEntity.StartsWith
    BitwardenCipher.Login.Uri.MatchType.Exact -> UriMatchTypeEntity.Exact
    BitwardenCipher.Login.Uri.MatchType.RegularExpression -> UriMatchTypeEntity.RegularExpression
    BitwardenCipher.Login.Uri.MatchType.Never -> UriMatchTypeEntity.Never
}

fun UriMatchTypeEntity.domain() = when (this) {
    UriMatchTypeEntity.Domain -> BitwardenCipher.Login.Uri.MatchType.Domain
    UriMatchTypeEntity.Host -> BitwardenCipher.Login.Uri.MatchType.Host
    UriMatchTypeEntity.StartsWith -> BitwardenCipher.Login.Uri.MatchType.StartsWith
    UriMatchTypeEntity.Exact -> BitwardenCipher.Login.Uri.MatchType.Exact
    UriMatchTypeEntity.RegularExpression -> BitwardenCipher.Login.Uri.MatchType.RegularExpression
    UriMatchTypeEntity.Never -> BitwardenCipher.Login.Uri.MatchType.Never
}
