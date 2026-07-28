package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.SourceBinding
import com.artemchep.keyguard.core.store.bitwarden.SourceFieldRef
import com.artemchep.keyguard.core.store.bitwarden.SourceProjectionId
import com.artemchep.keyguard.core.store.bitwarden.SourceRepresentation
import com.artemchep.keyguard.core.store.bitwarden.SourceRole

/**
 * Parser contract for KeePass OTP/TOTP/HOTP fields.
 *
 *
 * Decode projections, in priority order:
 *
 * | Projection        | Fields consumed when projection wins                         |
 * |-------------------|--------------------------------------------------------------|
 * | Single field      | `otp` or `OTPAuth`. Accepts `otpauth://`, opaque OTP URIs,   |
 * |                   | KeeOTP query strings, or raw/base32 secrets.                 |
 * | KeeOTP seed       | `TOTP Seed` plus optional `TOTP Settings`.                   |
 * | TimeOtp           | `TimeOtp-Secret-Base32`, `TimeOtp-Secret`,                   |
 * |                   | `TimeOtp-Secret-Hex`, `TimeOtp-Secret-Base64`; optional      |
 * |                   | `TimeOtp-Period`, `TimeOtp-Length`, `TimeOtp-Algorithm`.     |
 * | HmacOtp           | `HmacOtp-Secret-Base32`, `HmacOtp-Secret`,                   |
 * |                   | `HmacOtp-Secret-Hex`, `HmacOtp-Secret-Base64`, and required  |
 * |                   | `HmacOtp-Counter`.                                           |
 *
 * Only the winning projection's fields are consumed and bound to
 * [CipherSourceCanonicalPaths.LOGIN_TOTP]. Malformed sidecars, unsupported
 * secrets, non-winning projections, and unknown OTP-looking fields stay
 * available for the top-level custom-field parser.
 *
 * Encode removes bound OTP fields when the canonical value is empty.
 * Existing bindings preserve source field names, secret encodings,
 * period/digit/algorithm/counter sidecars, and concealment.
 * Fresh unbound TOTP values that are valid raw Base32 or standard Base64
 * secrets with at least 10 decoded bytes are written as concealed
 * `TimeOtp-Secret-Base32` or `TimeOtp-Secret-Base64` fields. Other new TOTP
 * and HOTP values are written as concealed compatible `otp` `otpauth://...` values.
 * Opaque OTP URIs are stored verbatim in `otp`, `OTPAuth`, or the previously
 * bound free-form field instead of being normalized as base32 secrets.
 */
internal class KeePassTotpCodec(
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
) {
    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): DecodedTotp? {
        val source = listOfNotNull(
            decodeOtpField(remote, DEFAULT_TOTP_FIELD_KEY),
            decodeOtpField(remote, OTPAUTH_FIELD_KEY),
            decodeTotpSeed(remote),
            decodeTimeOtp(remote),
            decodeHmacOtp(remote),
        ).firstOrNull() ?: return null

        // Only the winning projection's fields are consumed; any co-present
        // alternate convention (e.g. a `TOTP Seed` beside the chosen `otp`) is
        // deliberately left to surface as a custom field so the user's data is
        // preserved rather than silently dropped.
        source.sourceFields.forEach { field ->
            scope.consumeField(key = field.key)
        }

        val binding = sourceBinding(
            canonicalPaths = listOf(CipherSourceCanonicalPaths.LOGIN_TOTP),
            sourceFields = source.sourceFields.map { field ->
                decodedSourceFieldRef(
                    remote = remote,
                    key = field.key,
                    value = field.value,
                    role = field.role,
                    representationId = field.representationId,
                )
            },
            projectionId = source.projectionId,
        )
        return DecodedTotp(
            value = source.value,
            binding = binding,
        )
    }

    fun encode(
        canonicalTotp: String?,
        sourceData: CipherSourceData?,
    ): EncodedTotp {
        if (canonicalTotp.isNullOrEmpty()) {
            return EncodedTotp(
                writes = emptyList(),
                sourceData = sourceData.rebuildKeepass(
                    stripPaths = setOf(CipherSourceCanonicalPaths.LOGIN_TOTP),
                    newBindings = emptyList(),
                ),
            )
        }

        val binding = sourceData
            .keepassBindingsFor(setOf(CipherSourceCanonicalPaths.LOGIN_TOTP))
            .bindingFor(CipherSourceCanonicalPaths.LOGIN_TOTP)

        val encoded = if (isOpaqueOtpUri(canonicalTotp)) {
            // Non-otpauth schemes (steam://, motp://, ...) are not base32
            // secrets and must never be normalized; preserve them verbatim.
            encodeOpaqueUri(canonicalTotp, binding)
        } else {
            val config = parseCanonicalOtp(canonicalTotp)
            if (binding != null) {
                encodeWithBinding(canonicalTotp, config, binding)
            } else {
                encodeNew(canonicalTotp, config)
            }
        }

        return EncodedTotp(
            writes = encoded.writes,
            sourceData = sourceData.rebuildKeepass(
                stripPaths = setOf(CipherSourceCanonicalPaths.LOGIN_TOTP),
                newBindings = listOf(encoded.binding),
            ),
        )
    }

    private fun decodeOtpField(
        remote: Entry,
        key: String,
    ): DecodedTotpSource? {
        val value = remote.fields[key]
            ?: return null
        val parsed = parseOtpFieldValue(value.content)
            ?: return null
        return decodedSource(
            value = parsed.value,
            projectionId = KeePassTotpProjectionIds.SINGLE_FIELD,
            representationId = parsed.representationId,
            sourceFields = listOf(
                DecodedTotpSourceField(
                    key = key,
                    value = value,
                    role = SOURCE_ROLE_TOKEN,
                    representationId = parsed.representationId,
                ),
            ),
        )
    }

    private fun decodeTotpSeed(remote: Entry): DecodedTotpSource? {
        val seed = remote.fields[TOTP_SEED_FIELD_KEY]
            ?: return null
        val secret = normalizeBase32Secret(seed.content)
            .takeIf { it.isNotEmpty() }
            ?: return null
        val settings = remote.fields[TOTP_SETTINGS_FIELD_KEY]
            ?.let { value -> parseTotpSettings(value.content)?.let { value to it } }
        val value = settings
            ?.second
            ?.toTotpValue(secret)
            ?: secret
        val sourceFields = buildList {
            add(
                DecodedTotpSourceField(
                    key = TOTP_SEED_FIELD_KEY,
                    value = seed,
                    role = SOURCE_ROLE_TOKEN,
                    representationId = KeePassTotpRepresentationIds.BASE32_SECRET,
                ),
            )
            settings?.let { (fieldValue, _) ->
                add(
                    DecodedTotpSourceField(
                        key = TOTP_SETTINGS_FIELD_KEY,
                        value = fieldValue,
                        role = SOURCE_ROLE_SETTINGS,
                        representationId = KeePassTotpRepresentationIds.TOTP_SETTINGS,
                    ),
                )
            }
        }
        return decodedSource(
            value = value,
            projectionId = KeePassTotpProjectionIds.TOTP_SEED_SETTINGS,
            representationId = KeePassTotpRepresentationIds.TOTP_SEED_SETTINGS,
            sourceFields = sourceFields,
        )
    }

    private fun decodeTimeOtp(remote: Entry): DecodedTotpSource? {
        val secretSource = TIME_OTP_SECRET_FIELDS
            .firstNotNullOfOrNull { field ->
                remote.fields[field.key]
                    ?.let { value -> decodeSecretField(field, value)?.let { value to it } }
            } ?: return null
        val (secretFieldValue, secretField) = secretSource
        val period = remote.fields[TIME_OTP_PERIOD_FIELD_KEY]
            ?.let { value -> parsePositiveInt(value.content)?.let { value to it } }
        val digits = remote.fields[TIME_OTP_LENGTH_FIELD_KEY]
            ?.let { value -> parsePositiveInt(value.content)?.let { value to it } }
        val algorithm = remote.fields[TIME_OTP_ALGORITHM_FIELD_KEY]
            ?.let { value -> parseAlgorithm(value.content)?.let { value to it } }
        val config = OtpConfig.Totp(
            secretBase32 = secretField.secretBase32,
            period = period?.second ?: DEFAULT_TOTP_PERIOD,
            digits = digits?.second ?: DEFAULT_TOTP_DIGITS,
            algorithm = algorithm?.second ?: DEFAULT_ALGORITHM,
        )
        val hasSettings = period != null || digits != null || algorithm != null
        val value = if (hasSettings) {
            config.toValue(forceUri = true)
        } else {
            secretField.secretBase32
        }
        val projectionId = if (
            secretField.key == TIME_OTP_SECRET_BASE32_FIELD_KEY &&
            !hasSettings
        ) {
            KeePassTotpProjectionIds.SINGLE_FIELD
        } else {
            KeePassTotpProjectionIds.TIME_OTP
        }
        val sourceFields = buildList {
            add(
                DecodedTotpSourceField(
                    key = secretField.key,
                    value = secretFieldValue,
                    role = SOURCE_ROLE_TOKEN,
                    representationId = secretField.representationId,
                ),
            )
            period?.let { (fieldValue, _) ->
                add(
                    DecodedTotpSourceField(
                        key = TIME_OTP_PERIOD_FIELD_KEY,
                        value = fieldValue,
                        role = SOURCE_ROLE_PERIOD,
                        representationId = KeePassTotpRepresentationIds.TIME_OTP_PERIOD,
                    ),
                )
            }
            digits?.let { (fieldValue, _) ->
                add(
                    DecodedTotpSourceField(
                        key = TIME_OTP_LENGTH_FIELD_KEY,
                        value = fieldValue,
                        role = SOURCE_ROLE_DIGITS,
                        representationId = KeePassTotpRepresentationIds.TIME_OTP_LENGTH,
                    ),
                )
            }
            algorithm?.let { (fieldValue, _) ->
                add(
                    DecodedTotpSourceField(
                        key = TIME_OTP_ALGORITHM_FIELD_KEY,
                        value = fieldValue,
                        role = SOURCE_ROLE_ALGORITHM,
                        representationId = KeePassTotpRepresentationIds.TIME_OTP_ALGORITHM,
                    ),
                )
            }
        }
        return decodedSource(
            value = value,
            projectionId = projectionId,
            representationId = secretField.representationId,
            sourceFields = sourceFields,
        )
    }

    private fun decodeHmacOtp(remote: Entry): DecodedTotpSource? {
        val secretSource = HMAC_OTP_SECRET_FIELDS
            .firstNotNullOfOrNull { field ->
                remote.fields[field.key]
                    ?.let { value -> decodeSecretField(field, value)?.let { value to it } }
            } ?: return null
        val counter = remote.fields[HMAC_OTP_COUNTER_FIELD_KEY]
            ?.let { value -> parseNonNegativeLong(value.content)?.let { value to it } }
            ?: return null
        val (secretFieldValue, secretField) = secretSource
        val config = OtpConfig.Hotp(
            secretBase32 = secretField.secretBase32,
            counter = counter.second,
        )
        val sourceFields = listOf(
            DecodedTotpSourceField(
                key = secretField.key,
                value = secretFieldValue,
                role = SOURCE_ROLE_TOKEN,
                representationId = secretField.representationId,
            ),
            DecodedTotpSourceField(
                key = HMAC_OTP_COUNTER_FIELD_KEY,
                value = counter.first,
                role = SOURCE_ROLE_COUNTER,
                representationId = KeePassTotpRepresentationIds.HMAC_OTP_COUNTER,
            ),
        )
        return decodedSource(
            value = config.toValue(forceUri = true),
            projectionId = KeePassTotpProjectionIds.HMAC_OTP,
            representationId = secretField.representationId,
            sourceFields = sourceFields,
        )
    }

    private fun parseOtpFieldValue(value: String): ParsedOtpField? {
        val trimmed = value.trim()
        if (trimmed.startsWith(OTP_AUTH_PREFIX, ignoreCase = true)) {
            return ParsedOtpField(
                value = trimmed,
                representationId = if (trimmed.startsWith(HOTP_AUTH_PREFIX, ignoreCase = true)) {
                    KeePassTotpRepresentationIds.OTPAUTH_HOTP_URI
                } else {
                    KeePassTotpRepresentationIds.OTPAUTH_URI
                },
            )
        }
        if (trimmed.contains("://")) {
            // A non-otpauth scheme URI (steam://, motp://, ...) stored verbatim.
            return ParsedOtpField(
                value = trimmed,
                representationId = KeePassTotpRepresentationIds.OPAQUE_OTP_URI,
            )
        }
        parseKeeOtpQuery(trimmed)?.let { config ->
            return ParsedOtpField(
                value = config.toValue(forceUri = true),
                representationId = KeePassTotpRepresentationIds.KEEOTP_QUERY,
            )
        }
        val secret = normalizeBase32Secret(trimmed)
            .takeIf { it.isNotEmpty() }
            ?: return null
        return ParsedOtpField(
            value = secret,
            representationId = KeePassTotpRepresentationIds.BASE32_SECRET,
        )
    }

    private fun parseKeeOtpQuery(value: String): OtpConfig.Totp? {
        val params = parseQuery(value)
        val secret = params["key"]
            ?.let(::normalizeBase32Secret)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return OtpConfig.Totp(
            secretBase32 = secret,
            period = params["step"]?.let(::parsePositiveInt) ?: DEFAULT_TOTP_PERIOD,
            digits = params["size"]?.let(::parsePositiveInt) ?: DEFAULT_TOTP_DIGITS,
            algorithm = params["otpHashMode"]?.let(::parseAlgorithm) ?: DEFAULT_ALGORITHM,
        )
    }

    private fun parseCanonicalOtp(value: String): OtpConfig {
        val trimmed = value.trim()
        if (trimmed.startsWith(OTP_AUTH_PREFIX, ignoreCase = true)) {
            val afterPrefix = trimmed.substring(OTP_AUTH_PREFIX.length)
            val type = afterPrefix.substringBefore('/').substringBefore('?').lowercase()
            val params = parseQuery(afterPrefix.substringAfter('?', ""))
            val secret = params["secret"]
                ?.let(::normalizeBase32Secret)
                ?.takeIf { it.isNotEmpty() }
                ?: trimmed
            if (type == "hotp") {
                return OtpConfig.Hotp(
                    secretBase32 = secret,
                    counter = params["counter"]?.let(::parseNonNegativeLong) ?: DEFAULT_HOTP_COUNTER,
                    digits = params["digits"]?.let(::parsePositiveInt) ?: DEFAULT_TOTP_DIGITS,
                    algorithm = params["algorithm"]?.let(::parseAlgorithm) ?: DEFAULT_ALGORITHM,
                )
            }
            return OtpConfig.Totp(
                secretBase32 = secret,
                period = params["period"]?.let(::parsePositiveInt) ?: DEFAULT_TOTP_PERIOD,
                digits = params["digits"]?.let(::parsePositiveInt) ?: DEFAULT_TOTP_DIGITS,
                algorithm = params["algorithm"]?.let(::parseAlgorithm) ?: DEFAULT_ALGORITHM,
            )
        }
        parseKeeOtpQuery(trimmed)?.let { return it }
        return OtpConfig.Totp(secretBase32 = normalizeBase32Secret(trimmed))
    }

    private fun encodeWithBinding(
        canonicalTotp: String,
        config: OtpConfig,
        binding: SourceBinding,
    ): EncodedBindingTotp {
        val sourceFields = binding.sourceFields
        val secretField = sourceFields.firstOrNull { it.role == SOURCE_ROLE_TOKEN }
            ?: sourceFields.firstOrNull { it.key.isNotBlank() }
        return when (binding.projection.id) {
            KeePassTotpProjectionIds.TOTP_SEED_SETTINGS -> encodeTotpSeedSettings(config, binding)
            KeePassTotpProjectionIds.TIME_OTP -> encodeTimeOtp(config, binding)
            KeePassTotpProjectionIds.HMAC_OTP -> encodeHmacOtp(config, binding)
            else -> encodeSingleField(canonicalTotp, config, binding, secretField)
        }
    }

    private fun encodeSingleField(
        canonicalTotp: String,
        config: OtpConfig,
        binding: SourceBinding,
        sourceField: SourceFieldRef?,
    ): EncodedBindingTotp {
        val representationId = sourceField?.representationId
            ?: representationIdOf(canonicalTotp)
        val key = sourceField?.key ?: defaultFieldKey(representationId)
        // SECU-3: the single field carries the full OTP secret; always conceal
        // it, even when the source field was stored unprotected.
        val concealed = true
        val value = singleFieldValue(
            key = key,
            representationId = representationId,
            canonicalTotp = canonicalTotp,
            config = config,
        )
        val binding2 = binding.takeIf { sourceField != null } ?: newBinding(
            projectionId = KeePassTotpProjectionIds.SINGLE_FIELD,
            sourceFields = listOf(newSourceField(key, representationId, concealed)),
        )
        return EncodedBindingTotp(
            writes = listOf(KeePassFieldWrite(key, keepassEntryValue(value, concealed))),
            binding = binding2,
        )
    }

    private fun encodeTotpSeedSettings(
        config: OtpConfig,
        binding: SourceBinding,
    ): EncodedBindingTotp {
        val totp = config.asTotp()
        val seedField = binding.sourceFields.firstOrNull { it.key == TOTP_SEED_FIELD_KEY }
            ?: newSourceField(TOTP_SEED_FIELD_KEY, KeePassTotpRepresentationIds.BASE32_SECRET)
        val settingsField = binding.sourceFields.firstOrNull { it.key == TOTP_SETTINGS_FIELD_KEY }
        val writes = buildList {
            // SECU-3: the seed is the secret; always conceal it.
            add(KeePassFieldWrite(seedField.key, keepassEntryValue(totp.secretBase32, concealed = true)))
            if (settingsField != null || totp.hasNonDefaultTotpSettings()) {
                val field = settingsField
                    ?: newSourceField(TOTP_SETTINGS_FIELD_KEY, KeePassTotpRepresentationIds.TOTP_SETTINGS, false)
                add(
                    KeePassFieldWrite(
                        field.key,
                        keepassEntryValue("${totp.period};${totp.digits}", field.isConcealedByDefault(default = false)),
                    ),
                )
            }
        }
        return EncodedBindingTotp(writes = writes, binding = binding)
    }

    private fun encodeTimeOtp(
        config: OtpConfig,
        binding: SourceBinding,
    ): EncodedBindingTotp {
        val totp = config.asTotp()
        val secretField = binding.sourceFields.firstOrNull { it.role == SOURCE_ROLE_TOKEN }
            ?: newSourceField(TIME_OTP_SECRET_BASE32_FIELD_KEY, KeePassTotpRepresentationIds.BASE32_SECRET)
        val writes = buildList {
            add(
                KeePassFieldWrite(
                    secretField.key,
                    // SECU-3: the secret is always concealed.
                    keepassEntryValue(encodeSecretForField(secretField.key, totp.secretBase32), concealed = true),
                ),
            )
            val periodField = binding.sourceFields.firstOrNull { it.key == TIME_OTP_PERIOD_FIELD_KEY }
            if (periodField != null || totp.period != DEFAULT_TOTP_PERIOD) {
                val field = periodField
                    ?: newSourceField(TIME_OTP_PERIOD_FIELD_KEY, KeePassTotpRepresentationIds.TIME_OTP_PERIOD, false)
                add(KeePassFieldWrite(field.key, keepassEntryValue(totp.period.toString(), field.isConcealedByDefault(default = false))))
            }
            val lengthField = binding.sourceFields.firstOrNull { it.key == TIME_OTP_LENGTH_FIELD_KEY }
            if (lengthField != null || totp.digits != DEFAULT_TOTP_DIGITS) {
                val field = lengthField
                    ?: newSourceField(TIME_OTP_LENGTH_FIELD_KEY, KeePassTotpRepresentationIds.TIME_OTP_LENGTH, false)
                add(KeePassFieldWrite(field.key, keepassEntryValue(totp.digits.toString(), field.isConcealedByDefault(default = false))))
            }
            val algorithmField = binding.sourceFields.firstOrNull { it.key == TIME_OTP_ALGORITHM_FIELD_KEY }
            if (algorithmField != null || totp.algorithm != DEFAULT_ALGORITHM) {
                val field = algorithmField
                    ?: newSourceField(TIME_OTP_ALGORITHM_FIELD_KEY, KeePassTotpRepresentationIds.TIME_OTP_ALGORITHM, false)
                add(KeePassFieldWrite(field.key, keepassEntryValue(totp.algorithm.hmacName, field.isConcealedByDefault(default = false))))
            }
        }
        return EncodedBindingTotp(writes = writes, binding = binding)
    }

    private fun encodeHmacOtp(
        config: OtpConfig,
        binding: SourceBinding,
    ): EncodedBindingTotp {
        val hotp = config.asHotp()
        val secretField = binding.sourceFields.firstOrNull { it.role == SOURCE_ROLE_TOKEN }
            ?: newSourceField(HMAC_OTP_SECRET_BASE32_FIELD_KEY, KeePassTotpRepresentationIds.BASE32_SECRET)
        val counterField = binding.sourceFields.firstOrNull { it.key == HMAC_OTP_COUNTER_FIELD_KEY }
            ?: newSourceField(HMAC_OTP_COUNTER_FIELD_KEY, KeePassTotpRepresentationIds.HMAC_OTP_COUNTER, false)
        return EncodedBindingTotp(
            writes = listOf(
                KeePassFieldWrite(
                    secretField.key,
                    // SECU-3: the secret is always concealed.
                    keepassEntryValue(encodeSecretForField(secretField.key, hotp.secretBase32), concealed = true),
                ),
                KeePassFieldWrite(
                    counterField.key,
                    keepassEntryValue(hotp.counter.toString(), counterField.isConcealedByDefault(default = false)),
                ),
            ),
            binding = binding,
        )
    }

    private fun encodeNew(
        canonicalTotp: String,
        config: OtpConfig,
    ): EncodedBindingTotp {
        if (config is OtpConfig.Totp) {
            classifyRawTimeOtpSecret(canonicalTotp)?.let { secret ->
                return encodeNewTimeOtpSecret(secret)
            }
        }

        // URI, KeeOTP query, HOTP, and non-secret-shaped values continue using
        // KeePassXC's `otp` field as an otpauth:// URI.
        val key = DEFAULT_TOTP_FIELD_KEY
        val representationId = if (config is OtpConfig.Hotp) {
            KeePassTotpRepresentationIds.OTPAUTH_HOTP_URI
        } else {
            KeePassTotpRepresentationIds.OTPAUTH_URI
        }
        val value = if (canonicalTotp.trim().startsWith(OTP_AUTH_PREFIX, ignoreCase = true)) {
            canonicalTotp
        } else {
            config.toValue(forceUri = true)
        }
        val concealed = true
        return EncodedBindingTotp(
            writes = listOf(
                KeePassFieldWrite(key, keepassEntryValue(value, concealed)),
            ),
            binding = newBinding(
                projectionId = KeePassTotpProjectionIds.SINGLE_FIELD,
                sourceFields = listOf(newSourceField(key, representationId, concealed)),
            ),
        )
    }

    private fun encodeNewTimeOtpSecret(
        secret: RawTimeOtpSecret,
    ): EncodedBindingTotp {
        val concealed = true
        return EncodedBindingTotp(
            writes = listOf(
                KeePassFieldWrite(secret.key, keepassEntryValue(secret.value, concealed)),
            ),
            binding = newBinding(
                projectionId = KeePassTotpProjectionIds.TIME_OTP,
                sourceFields = listOf(newSourceField(secret.key, secret.representationId, concealed)),
            ),
        )
    }

    private fun isOpaqueOtpUri(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.contains("://") &&
                !trimmed.startsWith(OTP_AUTH_PREFIX, ignoreCase = true)
    }

    private fun encodeOpaqueUri(
        canonicalTotp: String,
        binding: SourceBinding?,
    ): EncodedBindingTotp {
        // Reuse an existing free-form OTP field (otp / OTPAuth) if the entry was
        // already projected onto one; otherwise default to the conventional
        // `otp` field. Never reuse a base32-secret field key, which would write
        // a scheme URI into a field other clients read as a base32 secret.
        val key = binding
            ?.takeIf { it.projection.id == KeePassTotpProjectionIds.SINGLE_FIELD }
            ?.sourceFields
            ?.firstOrNull { it.role == SOURCE_ROLE_TOKEN && it.key in VERBATIM_OTP_FIELD_KEYS }
            ?.key
            ?: DEFAULT_TOTP_FIELD_KEY
        val representationId = KeePassTotpRepresentationIds.OPAQUE_OTP_URI
        // The value carries the full secret, so it is always concealed.
        val concealed = true
        return EncodedBindingTotp(
            writes = listOf(
                KeePassFieldWrite(key, keepassEntryValue(canonicalTotp, concealed)),
            ),
            binding = newBinding(
                projectionId = KeePassTotpProjectionIds.SINGLE_FIELD,
                sourceFields = listOf(newSourceField(key, representationId, concealed)),
            ),
        )
    }

    private fun newBinding(
        projectionId: SourceProjectionId,
        sourceFields: List<SourceFieldRef>,
    ): SourceBinding = sourceBinding(
        canonicalPaths = listOf(CipherSourceCanonicalPaths.LOGIN_TOTP),
        sourceFields = sourceFields,
        projectionId = projectionId,
    )

    private fun newSourceField(
        key: String,
        representationId: SourceRepresentation,
        concealed: Boolean = true,
    ): SourceFieldRef = encodedSourceFieldRef(
        key = key,
        concealed = concealed,
        role = SOURCE_ROLE_TOKEN,
        representationId = representationId,
    )

    private fun decodedSource(
        value: String,
        projectionId: SourceProjectionId,
        representationId: SourceRepresentation,
        sourceFields: List<DecodedTotpSourceField>,
    ): DecodedTotpSource = DecodedTotpSource(
        value = value,
        projectionId = projectionId,
        representationId = representationId,
        sourceFields = sourceFields,
    )

    private fun singleFieldValue(
        key: String,
        representationId: SourceRepresentation,
        canonicalTotp: String,
        config: OtpConfig,
    ): String = when {
        key == TIME_OTP_SECRET_BASE32_FIELD_KEY -> config.asTotp().secretBase32
        representationId == KeePassTotpRepresentationIds.BASE32_SECRET -> config.asTotp().secretBase32
        else -> canonicalTotp
    }

    private fun representationIdOf(
        key: String,
        value: String,
    ): SourceRepresentation =
        if (key == TIME_OTP_SECRET_BASE32_FIELD_KEY) {
            KeePassTotpRepresentationIds.BASE32_SECRET
        } else {
            representationIdOf(value)
        }

    private fun representationIdOf(value: String): SourceRepresentation =
        when {
            value.startsWith(HOTP_AUTH_PREFIX, ignoreCase = true) -> KeePassTotpRepresentationIds.OTPAUTH_HOTP_URI
            value.startsWith(OTP_AUTH_PREFIX, ignoreCase = true) -> KeePassTotpRepresentationIds.OTPAUTH_URI
            else -> KeePassTotpRepresentationIds.BASE32_SECRET
        }

    private fun defaultFieldKey(representationId: SourceRepresentation): String =
        if (representationId == KeePassTotpRepresentationIds.BASE32_SECRET) {
            TIME_OTP_SECRET_BASE32_FIELD_KEY
        } else {
            DEFAULT_TOTP_FIELD_KEY
        }

    private fun decodeSecretField(
        field: SecretField,
        value: EntryValue,
    ): DecodedSecretField? {
        val secretBase32 = when (field.encoding) {
            SecretEncoding.BASE32 -> normalizeBase32Secret(value.content)
            SecretEncoding.RAW -> encodeBase32(value.content.encodeToByteArray())
            SecretEncoding.HEX -> decodeHex(value.content)?.let(::encodeBase32)
            SecretEncoding.BASE64 -> runCatching {
                base64Service.decode(value.content)
            }.getOrNull()?.let(::encodeBase32)
        }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return DecodedSecretField(
            key = field.key,
            representationId = field.representationId,
            secretBase32 = secretBase32,
        )
    }

    private fun encodeSecretForField(
        key: String,
        secretBase32: String,
    ): String = when {
        key.endsWith("-Hex") -> decodeBase32(secretBase32)?.toHexString() ?: secretBase32
        key.endsWith("-Base64") -> decodeBase32(secretBase32)?.let(base64Service::encodeToString) ?: secretBase32
        key.endsWith("-Base32") -> secretBase32
        else -> decodeBase32(secretBase32)?.decodeToString() ?: secretBase32
    }

    private fun encodeBase32(bytes: ByteArray): String =
        base32Service.encodeToString(bytes).trimEnd('=')

    private fun decodeBase32(value: String): ByteArray? = runCatching {
        base32Service.decode(normalizeBase32Secret(value))
    }.getOrNull()

    private fun normalizeBase32Secret(value: String): String =
        value.filterNot { it.isWhitespace() }.trimEnd('=')

    private fun classifyRawTimeOtpSecret(value: String): RawTimeOtpSecret? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("://")) return null
        if (parseKeeOtpQuery(trimmed) != null) return null

        canonicalRawBase32SecretOrNull(trimmed)?.let { secret ->
            return RawTimeOtpSecret(
                key = TIME_OTP_SECRET_BASE32_FIELD_KEY,
                representationId = KeePassTotpRepresentationIds.BASE32_SECRET,
                value = secret,
            )
        }
        canonicalRawBase64SecretOrNull(trimmed)?.let { secret ->
            return RawTimeOtpSecret(
                key = TIME_OTP_SECRET_BASE64_FIELD_KEY,
                representationId = KeePassTotpRepresentationIds.TIME_OTP_SECRET_BASE64,
                value = secret,
            )
        }
        return null
    }

    private fun canonicalRawBase32SecretOrNull(value: String): String? {
        val normalized = value
            .filterNot { it.isWhitespace() }
            .takeIf { it.matches(BASE32_SECRET_PATTERN) }
            ?.trimEnd('=')
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (normalized.length % BASE32_ENCODED_BLOCK_SIZE !in BASE32_UNPADDED_LENGTH_REMAINDERS) {
            return null
        }

        val decoded = decodeBase32(normalized)
            ?.takeIf { it.size >= MIN_RAW_OTP_SECRET_BYTES }
            ?: return null
        return encodeBase32(decoded)
            .takeIf { it == normalized }
    }

    private fun canonicalRawBase64SecretOrNull(value: String): String? {
        val normalized = value
            .filterNot { it.isWhitespace() }
            .takeIf { it.length % BASE64_ENCODED_BLOCK_SIZE == 0 }
            ?.takeIf { it.matches(BASE64_SECRET_PATTERN) }
            ?: return null

        val decoded = runCatching {
            base64Service.decode(normalized)
        }.getOrNull()
            ?.takeIf { it.size >= MIN_RAW_OTP_SECRET_BYTES }
            ?: return null
        return base64Service.encodeToString(decoded)
            .takeIf { it == normalized }
    }

    private fun parseTotpSettings(value: String): TotpSettings? {
        val parts = value.split(';')
        if (parts.size !in 2..3) return null
        val period = parsePositiveInt(parts[0]) ?: return null
        val digits = if (parts[1].equals("S", ignoreCase = true)) {
            STEAM_DIGITS
        } else {
            parsePositiveInt(parts[1]) ?: return null
        }
        if (parts.size == 3 && !parts[2].startsWith("http://") && !parts[2].startsWith("https://")) {
            return null
        }
        return TotpSettings(period = period, digits = digits)
    }

    private fun parseQuery(value: String): Map<String, String> =
        value.split('&')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                part.substring(0, index) to part.substring(index + 1)
            }
            .toMap()

    private fun parsePositiveInt(value: String): Int? =
        value.toIntOrNull()?.takeIf { it > 0 }

    private fun parseNonNegativeLong(value: String): Long? =
        value.toLongOrNull()?.takeIf { it >= 0L }

    private fun parseAlgorithm(value: String): OtpAlgorithm? =
        when (value.uppercase()) {
            "SHA1", "SHA-1", "HMAC-SHA-1" -> OtpAlgorithm.SHA1
            "SHA256", "SHA-256", "HMAC-SHA-256" -> OtpAlgorithm.SHA256
            "SHA512", "SHA-512", "HMAC-SHA-512" -> OtpAlgorithm.SHA512
            else -> null
        }

    private fun TotpSettings.toTotpValue(secretBase32: String): String =
        OtpConfig.Totp(
            secretBase32 = secretBase32,
            period = period,
            digits = digits,
        ).toValue(forceUri = true)

    private fun OtpConfig.asTotp(): OtpConfig.Totp = when (this) {
        is OtpConfig.Totp -> this
        is OtpConfig.Hotp -> OtpConfig.Totp(
            secretBase32 = secretBase32,
            digits = digits,
            algorithm = algorithm,
        )
    }

    private fun OtpConfig.asHotp(): OtpConfig.Hotp = when (this) {
        is OtpConfig.Hotp -> this
        is OtpConfig.Totp -> OtpConfig.Hotp(
            secretBase32 = secretBase32,
            digits = digits,
            algorithm = algorithm,
        )
    }

    private fun OtpConfig.Totp.hasNonDefaultTotpSettings(): Boolean =
        period != DEFAULT_TOTP_PERIOD ||
                digits != DEFAULT_TOTP_DIGITS ||
                algorithm != DEFAULT_ALGORITHM

    private fun OtpConfig.toValue(forceUri: Boolean): String = when (this) {
        is OtpConfig.Totp -> {
            if (!forceUri && !hasNonDefaultTotpSettings()) {
                secretBase32
            } else {
                buildString {
                    append("otpauth://totp/?secret=")
                    append(secretBase32)
                    append("&period=")
                    append(period)
                    append("&digits=")
                    append(digits)
                    if (algorithm != DEFAULT_ALGORITHM) {
                        append("&algorithm=")
                        append(algorithm.otpauthName)
                    }
                }
            }
        }

        is OtpConfig.Hotp -> buildString {
            append("otpauth://hotp/?secret=")
            append(secretBase32)
            append("&counter=")
            append(counter)
            if (digits != DEFAULT_TOTP_DIGITS) {
                append("&digits=")
                append(digits)
            }
            if (algorithm != DEFAULT_ALGORITHM) {
                append("&algorithm=")
                append(algorithm.otpauthName)
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte ->
            (byte.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }

    private fun decodeHex(value: String): ByteArray? {
        val clean = value.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0) return null
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val offset = i * 2
            val byte = clean.substring(offset, offset + 2).toIntOrNull(16)
                ?: return null
            bytes[i] = byte.toByte()
        }
        return bytes
    }

    data class DecodedTotp(
        val value: String,
        val binding: SourceBinding,
    )

    data class EncodedTotp(
        val writes: List<KeePassFieldWrite>,
        val sourceData: CipherSourceData?,
    )

    private data class ParsedOtpField(
        val value: String,
        val representationId: SourceRepresentation,
    )

    private data class DecodedTotpSource(
        val value: String,
        val projectionId: SourceProjectionId,
        val representationId: SourceRepresentation,
        val sourceFields: List<DecodedTotpSourceField>,
    )

    private data class DecodedTotpSourceField(
        val key: String,
        val value: EntryValue,
        val role: SourceRole,
        val representationId: SourceRepresentation,
    )

    private data class EncodedBindingTotp(
        val writes: List<KeePassFieldWrite>,
        val binding: SourceBinding,
    )

    private data class SecretField(
        val key: String,
        val encoding: SecretEncoding,
        val representationId: SourceRepresentation,
    )

    private data class DecodedSecretField(
        val key: String,
        val representationId: SourceRepresentation,
        val secretBase32: String,
    )

    private data class RawTimeOtpSecret(
        val key: String,
        val representationId: SourceRepresentation,
        val value: String,
    )

    private data class TotpSettings(
        val period: Int,
        val digits: Int,
    )

    private sealed interface OtpConfig {
        val secretBase32: String
        val digits: Int
        val algorithm: OtpAlgorithm

        data class Totp(
            override val secretBase32: String,
            val period: Int = DEFAULT_TOTP_PERIOD,
            override val digits: Int = DEFAULT_TOTP_DIGITS,
            override val algorithm: OtpAlgorithm = DEFAULT_ALGORITHM,
        ) : OtpConfig

        data class Hotp(
            override val secretBase32: String,
            val counter: Long = DEFAULT_HOTP_COUNTER,
            override val digits: Int = DEFAULT_TOTP_DIGITS,
            override val algorithm: OtpAlgorithm = DEFAULT_ALGORITHM,
        ) : OtpConfig
    }

    private enum class OtpAlgorithm(
        val otpauthName: String,
        val hmacName: String,
    ) {
        SHA1("SHA1", "HMAC-SHA-1"),
        SHA256("SHA256", "HMAC-SHA-256"),
        SHA512("SHA512", "HMAC-SHA-512"),
    }

    private enum class SecretEncoding {
        RAW,
        HEX,
        BASE32,
        BASE64,
    }

    private companion object {
        const val DEFAULT_TOTP_FIELD_KEY = "otp"
        const val OTPAUTH_FIELD_KEY = "OTPAuth"
        const val TOTP_SEED_FIELD_KEY = "TOTP Seed"
        const val TOTP_SETTINGS_FIELD_KEY = "TOTP Settings"
        const val TIME_OTP_SECRET_BASE32_FIELD_KEY = "TimeOtp-Secret-Base32"
        const val TIME_OTP_SECRET_BASE64_FIELD_KEY = "TimeOtp-Secret-Base64"
        const val TIME_OTP_PERIOD_FIELD_KEY = "TimeOtp-Period"
        const val TIME_OTP_LENGTH_FIELD_KEY = "TimeOtp-Length"
        const val TIME_OTP_ALGORITHM_FIELD_KEY = "TimeOtp-Algorithm"
        const val HMAC_OTP_SECRET_BASE32_FIELD_KEY = "HmacOtp-Secret-Base32"
        const val HMAC_OTP_COUNTER_FIELD_KEY = "HmacOtp-Counter"

        const val OTP_AUTH_PREFIX = "otpauth://"
        const val HOTP_AUTH_PREFIX = "otpauth://hotp"

        // Free-form OTP fields that hold a verbatim OTP string / URI, as opposed
        // to fields whose name implies a specific secret encoding (e.g. base32).
        val VERBATIM_OTP_FIELD_KEYS = setOf(DEFAULT_TOTP_FIELD_KEY, OTPAUTH_FIELD_KEY)

        val SOURCE_ROLE_TOKEN = SourceRole("token")
        val SOURCE_ROLE_SETTINGS = SourceRole("settings")
        val SOURCE_ROLE_PERIOD = SourceRole("period")
        val SOURCE_ROLE_DIGITS = SourceRole("digits")
        val SOURCE_ROLE_ALGORITHM = SourceRole("algorithm")
        val SOURCE_ROLE_COUNTER = SourceRole("counter")

        const val DEFAULT_TOTP_PERIOD = 30
        const val DEFAULT_TOTP_DIGITS = 6
        const val DEFAULT_HOTP_COUNTER = 0L
        const val MIN_RAW_OTP_SECRET_BYTES = 10
        const val STEAM_DIGITS = 5
        val DEFAULT_ALGORITHM = OtpAlgorithm.SHA1

        const val BASE32_ENCODED_BLOCK_SIZE = 8
        val BASE32_UNPADDED_LENGTH_REMAINDERS = setOf(0, 2, 4, 5, 7)
        val BASE32_SECRET_PATTERN = Regex("^[A-Za-z2-7]+=*$")

        const val BASE64_ENCODED_BLOCK_SIZE = 4
        val BASE64_SECRET_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")

        val TIME_OTP_SECRET_FIELDS = listOf(
            SecretField("TimeOtp-Secret-Base32", SecretEncoding.BASE32, KeePassTotpRepresentationIds.BASE32_SECRET),
            SecretField("TimeOtp-Secret", SecretEncoding.RAW, KeePassTotpRepresentationIds.TIME_OTP_SECRET_RAW),
            SecretField("TimeOtp-Secret-Hex", SecretEncoding.HEX, KeePassTotpRepresentationIds.TIME_OTP_SECRET_HEX),
            SecretField(TIME_OTP_SECRET_BASE64_FIELD_KEY, SecretEncoding.BASE64, KeePassTotpRepresentationIds.TIME_OTP_SECRET_BASE64),
        )

        val HMAC_OTP_SECRET_FIELDS = listOf(
            SecretField("HmacOtp-Secret-Base32", SecretEncoding.BASE32, KeePassTotpRepresentationIds.BASE32_SECRET),
            SecretField("HmacOtp-Secret", SecretEncoding.RAW, KeePassTotpRepresentationIds.HMAC_OTP_SECRET_RAW),
            SecretField("HmacOtp-Secret-Hex", SecretEncoding.HEX, KeePassTotpRepresentationIds.HMAC_OTP_SECRET_HEX),
            SecretField("HmacOtp-Secret-Base64", SecretEncoding.BASE64, KeePassTotpRepresentationIds.HMAC_OTP_SECRET_BASE64),
        )
    }
}

internal object KeePassTotpProjectionIds {
    val SINGLE_FIELD = SourceProjectionId("keepass.totp.single-field")
    val TOTP_SEED_SETTINGS = SourceProjectionId("keepass.totp.seed-settings")
    val TIME_OTP = SourceProjectionId("keepass.totp.timeotp")
    val HMAC_OTP = SourceProjectionId("keepass.hotp.hmacotp")
}

internal object KeePassTotpRepresentationIds {
    val OTPAUTH_URI = SourceRepresentation("keepass.totp.otpauth-uri")
    val OTPAUTH_HOTP_URI = SourceRepresentation("keepass.hotp.otpauth-uri")
    val KEEOTP_QUERY = SourceRepresentation("keepass.totp.keeotp-query")
    val OPAQUE_OTP_URI = SourceRepresentation("keepass.totp.opaque-uri")
    val BASE32_SECRET = SourceRepresentation("keepass.totp.base32-secret")
    val TOTP_SETTINGS = SourceRepresentation("keepass.totp.settings")
    val TOTP_SEED_SETTINGS = SourceRepresentation("keepass.totp.seed-settings")
    val TIME_OTP_SECRET_RAW = SourceRepresentation("keepass.totp.timeotp.raw-secret")
    val TIME_OTP_SECRET_HEX = SourceRepresentation("keepass.totp.timeotp.hex-secret")
    val TIME_OTP_SECRET_BASE64 = SourceRepresentation("keepass.totp.timeotp.base64-secret")
    val TIME_OTP_PERIOD = SourceRepresentation("keepass.totp.timeotp.period")
    val TIME_OTP_LENGTH = SourceRepresentation("keepass.totp.timeotp.length")
    val TIME_OTP_ALGORITHM = SourceRepresentation("keepass.totp.timeotp.algorithm")
    val HMAC_OTP_SECRET_RAW = SourceRepresentation("keepass.hotp.hmacotp.raw-secret")
    val HMAC_OTP_SECRET_HEX = SourceRepresentation("keepass.hotp.hmacotp.hex-secret")
    val HMAC_OTP_SECRET_BASE64 = SourceRepresentation("keepass.hotp.hmacotp.base64-secret")
    val HMAC_OTP_COUNTER = SourceRepresentation("keepass.hotp.hmacotp.counter")
}
