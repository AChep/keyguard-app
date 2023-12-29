package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNames

object FuzzyLocalDateIso8601Serializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("keyguard.FuzzyLocalDate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate = kotlin.run {
        val isoString = decoder.decodeString()
        runCatching {
            LocalDate.parse(isoString)
        }.getOrElse {
            Instant.parse(isoString)
                .toLocalDateTime(TimeZone.UTC)
                .date
        }
    }

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

}

@Serializable
data class HibpBreachGroup(
    val breaches: List<HibpBreachResponse>,
)

// See:
// https://haveibeenpwned.com/API/v3#BreachModel
@Serializable
data class HibpBreachResponse(
    /**
     * A Pascal-cased name representing the breach which is unique across
     * all other breaches. This value never changes and may be used to name
     * dependent assets (such as images) but should not be shown directly
     * to end users (see the "Title" attribute instead).
     */
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    /**
     * A descriptive title for the breach suitable for displaying to end users.
     * It's unique across all breaches but individual values may change in the future
     * (i.e. if another breach occurs against an organisation already in the system).
     * If a stable value is required to reference the breach,
     * refer to the "Name" attribute instead.
     */
    @JsonNames("title")
    @SerialName("Title")
    val title: String? = null,
    /**
     * The domain of the primary website the breach occurred on. This may be used
     * for identifying other assets external systems may have for the site.
     */
    @JsonNames("domain")
    @SerialName("Domain")
    val domain: String? = null,
    /**
     * The date (with no time) the breach originally occurred on in ISO 8601 format.
     * This is not always accurate — frequently breaches are discovered and reported
     * long after the original incident. Use this attribute as a guide only.
     */
    @JsonNames("breachDate")
    @SerialName("BreachDate")
    @Serializable(with = FuzzyLocalDateIso8601Serializer::class)
    val breachDate: LocalDate? = null,
    @JsonNames("addedDate")
    @SerialName("AddedDate")
    @Serializable(with = FuzzyLocalDateIso8601Serializer::class)
    val addedDate: LocalDate? = null,
    @JsonNames("description")
    @SerialName("Description")
    val description: String? = null,
    @JsonNames("logoPath")
    @SerialName("LogoPath")
    val logoPath: String? = null,
    @JsonNames("pwnCount")
    @SerialName("PwnCount")
    val pwnCount: Int? = null,
    /**
     * This attribute describes the nature of the data compromised in the breach and
     * contains an alphabetically ordered string array of impacted data classes.
     */
    @JsonNames("dataClasses")
    @SerialName("DataClasses")
    val dataClasses: List<String> = emptyList(),
    /**
     * Indicates that the breach is considered unverified. An unverified breach may not
     * have been hacked from the indicated website. An unverified breach is still loaded
     * into HIBP when there's sufficient confidence that a significant portion
     * of the data is legitimate.
     */
    @JsonNames("isVerified")
    @SerialName("IsVerified")
    val isVerified: Boolean? = null,
    @JsonNames("isFabricated")
    @SerialName("IsFabricated")
    val isFabricated: Boolean? = null,
    @JsonNames("isSensitive")
    @SerialName("IsSensitive")
    val isSensitive: Boolean? = null,
    @JsonNames("isRetired")
    @SerialName("IsRetired")
    val isRetired: Boolean? = null,
    @JsonNames("isSpamList")
    @SerialName("IsSpamList")
    val isSpamList: Boolean? = null,
    @JsonNames("isMalware")
    @SerialName("IsMalware")
    val isMalware: Boolean? = null,
)
