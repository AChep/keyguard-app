package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(LinkedIdTypeEntitySerializer::class)
enum class LinkedIdTypeEntity(
    override val int: Int,
) : IntEnum {
    Login_Username(100),
    Login_Password(101),

    // card
    Card_CardholderName(300),
    Card_ExpMonth(301),
    Card_ExpYear(302),
    Card_Code(303),
    Card_Brand(304),
    Card_Number(305),

    // identity
    Identity_Title(400),
    Identity_MiddleName(401),
    Identity_Address1(402),
    Identity_Address2(403),
    Identity_Address3(404),
    Identity_City(405),
    Identity_State(406),
    Identity_PostalCode(407),
    Identity_Country(408),
    Identity_Company(409),
    Identity_Email(410),
    Identity_Phone(411),
    Identity_Ssn(412),
    Identity_Username(413),
    Identity_PassportNumber(414),
    Identity_LicenseNumber(415),
    Identity_FirstName(416),
    Identity_LastName(417),
    Identity_FullName(418),
    ;

    companion object
}

object LinkedIdTypeEntitySerializer :
    CommonEnumIntSerializer<LinkedIdTypeEntity>(LinkedIdTypeEntity::class)

fun LinkedIdTypeEntity.Companion.of(
    model: BitwardenCipher.Field.LinkedId,
) = when (model) {
    BitwardenCipher.Field.LinkedId.Login_Username -> LinkedIdTypeEntity.Login_Username
    BitwardenCipher.Field.LinkedId.Login_Password -> LinkedIdTypeEntity.Login_Password
    BitwardenCipher.Field.LinkedId.Card_CardholderName -> LinkedIdTypeEntity.Card_CardholderName
    BitwardenCipher.Field.LinkedId.Card_ExpMonth -> LinkedIdTypeEntity.Card_ExpMonth
    BitwardenCipher.Field.LinkedId.Card_ExpYear -> LinkedIdTypeEntity.Card_ExpYear
    BitwardenCipher.Field.LinkedId.Card_Code -> LinkedIdTypeEntity.Card_Code
    BitwardenCipher.Field.LinkedId.Card_Brand -> LinkedIdTypeEntity.Card_Brand
    BitwardenCipher.Field.LinkedId.Card_Number -> LinkedIdTypeEntity.Card_Number
    BitwardenCipher.Field.LinkedId.Identity_Title -> LinkedIdTypeEntity.Identity_Title
    BitwardenCipher.Field.LinkedId.Identity_MiddleName -> LinkedIdTypeEntity.Identity_MiddleName
    BitwardenCipher.Field.LinkedId.Identity_Address1 -> LinkedIdTypeEntity.Identity_Address1
    BitwardenCipher.Field.LinkedId.Identity_Address2 -> LinkedIdTypeEntity.Identity_Address2
    BitwardenCipher.Field.LinkedId.Identity_Address3 -> LinkedIdTypeEntity.Identity_Address3
    BitwardenCipher.Field.LinkedId.Identity_City -> LinkedIdTypeEntity.Identity_City
    BitwardenCipher.Field.LinkedId.Identity_State -> LinkedIdTypeEntity.Identity_State
    BitwardenCipher.Field.LinkedId.Identity_PostalCode -> LinkedIdTypeEntity.Identity_PostalCode
    BitwardenCipher.Field.LinkedId.Identity_Country -> LinkedIdTypeEntity.Identity_Country
    BitwardenCipher.Field.LinkedId.Identity_Company -> LinkedIdTypeEntity.Identity_Company
    BitwardenCipher.Field.LinkedId.Identity_Email -> LinkedIdTypeEntity.Identity_Email
    BitwardenCipher.Field.LinkedId.Identity_Phone -> LinkedIdTypeEntity.Identity_Phone
    BitwardenCipher.Field.LinkedId.Identity_Ssn -> LinkedIdTypeEntity.Identity_Ssn
    BitwardenCipher.Field.LinkedId.Identity_Username -> LinkedIdTypeEntity.Identity_Username
    BitwardenCipher.Field.LinkedId.Identity_PassportNumber -> LinkedIdTypeEntity.Identity_PassportNumber
    BitwardenCipher.Field.LinkedId.Identity_LicenseNumber -> LinkedIdTypeEntity.Identity_LicenseNumber
    BitwardenCipher.Field.LinkedId.Identity_FirstName -> LinkedIdTypeEntity.Identity_FirstName
    BitwardenCipher.Field.LinkedId.Identity_LastName -> LinkedIdTypeEntity.Identity_LastName
    BitwardenCipher.Field.LinkedId.Identity_FullName -> LinkedIdTypeEntity.Identity_FullName
}

fun LinkedIdTypeEntity.domain() = when (this) {
    LinkedIdTypeEntity.Login_Username -> BitwardenCipher.Field.LinkedId.Login_Username
    LinkedIdTypeEntity.Login_Password -> BitwardenCipher.Field.LinkedId.Login_Password
    LinkedIdTypeEntity.Card_CardholderName -> BitwardenCipher.Field.LinkedId.Card_CardholderName
    LinkedIdTypeEntity.Card_ExpMonth -> BitwardenCipher.Field.LinkedId.Card_ExpMonth
    LinkedIdTypeEntity.Card_ExpYear -> BitwardenCipher.Field.LinkedId.Card_ExpYear
    LinkedIdTypeEntity.Card_Code -> BitwardenCipher.Field.LinkedId.Card_Code
    LinkedIdTypeEntity.Card_Brand -> BitwardenCipher.Field.LinkedId.Card_Brand
    LinkedIdTypeEntity.Card_Number -> BitwardenCipher.Field.LinkedId.Card_Number
    LinkedIdTypeEntity.Identity_Title -> BitwardenCipher.Field.LinkedId.Identity_Title
    LinkedIdTypeEntity.Identity_MiddleName -> BitwardenCipher.Field.LinkedId.Identity_MiddleName
    LinkedIdTypeEntity.Identity_Address1 -> BitwardenCipher.Field.LinkedId.Identity_Address1
    LinkedIdTypeEntity.Identity_Address2 -> BitwardenCipher.Field.LinkedId.Identity_Address2
    LinkedIdTypeEntity.Identity_Address3 -> BitwardenCipher.Field.LinkedId.Identity_Address3
    LinkedIdTypeEntity.Identity_City -> BitwardenCipher.Field.LinkedId.Identity_City
    LinkedIdTypeEntity.Identity_State -> BitwardenCipher.Field.LinkedId.Identity_State
    LinkedIdTypeEntity.Identity_PostalCode -> BitwardenCipher.Field.LinkedId.Identity_PostalCode
    LinkedIdTypeEntity.Identity_Country -> BitwardenCipher.Field.LinkedId.Identity_Country
    LinkedIdTypeEntity.Identity_Company -> BitwardenCipher.Field.LinkedId.Identity_Company
    LinkedIdTypeEntity.Identity_Email -> BitwardenCipher.Field.LinkedId.Identity_Email
    LinkedIdTypeEntity.Identity_Phone -> BitwardenCipher.Field.LinkedId.Identity_Phone
    LinkedIdTypeEntity.Identity_Ssn -> BitwardenCipher.Field.LinkedId.Identity_Ssn
    LinkedIdTypeEntity.Identity_Username -> BitwardenCipher.Field.LinkedId.Identity_Username
    LinkedIdTypeEntity.Identity_PassportNumber -> BitwardenCipher.Field.LinkedId.Identity_PassportNumber
    LinkedIdTypeEntity.Identity_LicenseNumber -> BitwardenCipher.Field.LinkedId.Identity_LicenseNumber
    LinkedIdTypeEntity.Identity_FirstName -> BitwardenCipher.Field.LinkedId.Identity_FirstName
    LinkedIdTypeEntity.Identity_LastName -> BitwardenCipher.Field.LinkedId.Identity_LastName
    LinkedIdTypeEntity.Identity_FullName -> BitwardenCipher.Field.LinkedId.Identity_FullName
}
