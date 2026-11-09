package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The public encryption keys of a device, by algorithm, see [RosterDevice.keys]: each a
 * base64url public key. The device may add or replace them, such as for a new algorithm or
 * larger keys: they are not part of its identity, and its self-signature covers them. A key
 * of an algorithm this build does not know, in any form, is kept and ignored.
 */
@Serializable(with = DeviceKeysSerializer::class)
class DeviceKeys(
    val raw: JsonObject,
) {
    /** The ML-KEM-1024 public key that relay storage seals to, if the device has one. */
    val mlKem1024: String? get() = raw.string(ML_KEM_1024)

    override fun equals(other: Any?): Boolean = this === other || other is DeviceKeys && raw == other.raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = "DeviceKeys(${raw.keys})"

    companion object {
        /** The name of an ML-KEM-1024 key. */
        const val ML_KEM_1024: String = "mlkem1024"

        val EMPTY: DeviceKeys = DeviceKeys(JsonObject(emptyMap()))

        /** Returns the keys of a device that has the ML-KEM-1024 key [mlKem1024]. */
        fun of(mlKem1024: String): DeviceKeys = DeviceKeys(JsonObject(mapOf(ML_KEM_1024 to JsonPrimitive(mlKem1024))))
    }
}

internal object DeviceKeysSerializer : RawObjectSerializer<DeviceKeys>(::DeviceKeys, DeviceKeys::raw)

/**
 * What a device supports, as its record says, see [RosterDevice.caps]. The group does what
 * every active device supports, see [com.artemchep.keyguard.feature.p2psync.roster.GroupCaps].
 *
 * @property rules the version of the rules the device follows, such as how concurrent
 *   versions of an entry compose, see [SyncEntry.body].
 * @property kinds the wire names of the entry kinds the device reads and writes, see
 *   [EntryKind.wireName].
 * @property features the optional features the user of the device turned on, see
 *   [DeviceFeature]. Unlike the [kinds], they say what the device allows, not what its
 *   build reads; the order does not count.
 */
@Serializable
data class DeviceCaps(
    val rules: Int,
    val kinds: List<String>,
    val features: List<String> = emptyList(),
) {
    companion object {
        /** The rules version of this build: 1, the only one. */
        const val RULES: Int = 1

        /** What this build supports: [RULES] and every [EntryKind], with no feature turned on. */
        val CURRENT: DeviceCaps = current(relayStorage = false)

        /**
         * What this build supports: [RULES] and every [EntryKind], and
         * [DeviceFeature.RELAY_STORAGE] if the user allows [relayStorage].
         */
        fun current(relayStorage: Boolean): DeviceCaps = DeviceCaps(
            rules = RULES,
            kinds = EntryKind.entries.map { it.wireName }.sorted(),
            features = listOfNotNull(DeviceFeature.RELAY_STORAGE.takeIf { relayStorage }),
        )
    }
}

/** The optional features a device lists in [DeviceCaps.features]. */
object DeviceFeature {
    /**
     * The user of the device lets the relay keep the state of the group for the devices
     * that are offline. The group uses it only once every active device lists it, see
     * [com.artemchep.keyguard.feature.p2psync.roster.GroupCaps.features]: every device holds
     * the same records, so one device that stores its state stores everyone's. A device
     * turns it off by signing its record without it.
     */
    const val RELAY_STORAGE: String = "relay-storage"
}

/**
 * A device of a sync group.
 *
 * A record is kept as its [raw] object, which its signatures cover: every field, also the
 * ones this build does not know, survives storage, merging and relaying. The other
 * properties are a view over it.
 *
 * The identity of a device is the record without its mutable fields, [label], [updatedAt],
 * [caps] and [keys], and without its signatures: it never changes. A field a newer build adds
 * is part of the identity; a key of a new algorithm goes into [keys].
 *
 * A device holds two signing keys. The device key, [signPub], signs its own record; the app
 * keeps it with the other keys of the vault. The authority key, [authorityPub], admits
 * devices and removes them, this one included; the app keeps it encrypted under the master
 * password alone. So a stolen device, even unlocked, can neither bring a device in nor
 * remove one, unless the thief knows the master password.
 * The device may change its [label], [caps] and [keys]; each change gets a newer [updatedAt], and of
 * two records of one device the newer one wins, see
 * [com.artemchep.keyguard.feature.p2psync.roster.RosterCrdt].
 *
 * @property id a random device id; a re-added device gets a new one.
 * @property label the name of the device, set by the device itself, see [DeviceRecordRules].
 * @property platform the platform the device runs on, for display only, see [DeviceRecordRules].
 * @property noisePub the base64url Noise static X25519 public key.
 * @property keys the public encryption keys of the device, such as the ML-KEM-1024 key that
 *   the items other devices leave for it on the relay are sealed to, see [DeviceKeys].
 * @property signPub the base64url Ed25519 public key of the device key.
 * @property authorityPub the base64url Ed25519 public key of the authority key; empty for a
 *   device that can not admit or remove devices.
 * @property addedAt when the inviter admitted the device.
 * @property addedBy the id of the inviter; the founder admits itself.
 * @property updatedAt when the device last changed its record; [addedAt] until then.
 * @property caps what the device supports.
 * @property admission the base64url signature of the authority key of the inviter over the
 *   identity.
 * @property selfSig the base64url signature of the device itself over the whole record but
 *   its signatures.
 */
@Serializable(with = RosterDeviceSerializer::class)
class RosterDevice private constructor(
    raw: JsonObject,
) : RawObject(raw) {
    private val fields: Fields = decodeFields(Fields.serializer(), raw)

    val id: String get() = fields.id

    val label: String get() = fields.label

    val platform: String get() = fields.platform

    val noisePub: String get() = fields.noisePub

    val keys: DeviceKeys get() = fields.keys

    val signPub: String get() = fields.signPub

    val authorityPub: String get() = fields.authorityPub

    val addedAt: Hlc get() = fields.addedAt

    val addedBy: String get() = fields.addedBy

    val updatedAt: Hlc get() = fields.updatedAt

    val caps: DeviceCaps get() = fields.caps

    val admission: String get() = fields.admission

    val selfSig: String get() = fields.selfSig

    /** Returns this record with the given fields; the others, also unknown ones, stay as they are. */
    // The fields of a record.
    @Suppress("LongParameterList")
    fun copy(
        id: String = this.id,
        label: String = this.label,
        platform: String = this.platform,
        noisePub: String = this.noisePub,
        keys: DeviceKeys = this.keys,
        signPub: String = this.signPub,
        authorityPub: String = this.authorityPub,
        addedAt: Hlc = this.addedAt,
        addedBy: String = this.addedBy,
        updatedAt: Hlc = this.updatedAt,
        caps: DeviceCaps = this.caps,
        admission: String = this.admission,
        selfSig: String = this.selfSig,
    ): RosterDevice {
        val after = Fields(
            id = id,
            label = label,
            platform = platform,
            noisePub = noisePub,
            keys = keys,
            signPub = signPub,
            authorityPub = authorityPub,
            addedAt = addedAt,
            addedBy = addedBy,
            updatedAt = updatedAt,
            caps = caps,
            admission = admission,
            selfSig = selfSig,
        )
        return RosterDevice(raw.withChanges(Fields.serializer(), fields, after))
    }

    override fun toString(): String = "RosterDevice($raw)"

    @Serializable
    private class Fields(
        val id: String,
        val label: String,
        val platform: String,
        val noisePub: String,
        val keys: DeviceKeys = DeviceKeys.EMPTY,
        val signPub: String,
        val authorityPub: String = "",
        val addedAt: Hlc,
        val addedBy: String,
        val updatedAt: Hlc = addedAt,
        val caps: DeviceCaps,
        val admission: String = "",
        val selfSig: String = "",
    )

    companion object {
        /** The fields of a record that change: the identity is the record without them. */
        internal val MUTABLE_FIELDS: Set<String> = setOf("label", "updatedAt", "caps", "keys")

        /** The fields of the signatures of a record. */
        internal const val FIELD_ADMISSION: String = "admission"
        internal const val FIELD_SELF_SIG: String = "selfSig"

        /** Returns a new record of the given fields. */
        // The fields of a record.
        @Suppress("LongParameterList")
        operator fun invoke(
            id: String,
            label: String,
            platform: String,
            noisePub: String,
            keys: DeviceKeys = DeviceKeys.EMPTY,
            signPub: String,
            authorityPub: String = "",
            addedAt: Hlc,
            addedBy: String,
            updatedAt: Hlc = addedAt,
            caps: DeviceCaps = DeviceCaps.CURRENT,
            admission: String = "",
            selfSig: String = "",
        ): RosterDevice {
            val fields = Fields(
                id = id,
                label = label,
                platform = platform,
                noisePub = noisePub,
                keys = keys,
                signPub = signPub,
                authorityPub = authorityPub,
                addedAt = addedAt,
                addedBy = addedBy,
                updatedAt = updatedAt,
                caps = caps,
                admission = admission,
                selfSig = selfSig,
            )
            return RosterDevice(encodeFields(Fields.serializer(), fields))
        }

        /**
         * Returns the record [raw], as received.
         *
         * @throws IllegalArgumentException if a field this build reads is malformed, or a
         *   number has no canonical form, see [CanonicalJson].
         */
        fun fromRaw(raw: JsonObject): RosterDevice = RosterDevice(raw)
    }
}

internal object RosterDeviceSerializer : RawObjectSerializer<RosterDevice>(RosterDevice::fromRaw, RosterDevice::raw)

/**
 * The rules of the fields of a device record that every device checks, see
 * [com.artemchep.keyguard.feature.p2psync.roster.RosterValidator]: a record that breaks
 * one is dropped.
 */
object DeviceRecordRules {
    /** The most Unicode code points of a [RosterDevice.label]. */
    const val MAX_LABEL_LENGTH: Int = 64

    /** The most characters of a [RosterDevice.platform]. */
    const val MAX_PLATFORM_LENGTH: Int = 32

    /**
     * The most bytes of the canonical JSON of a device record, its signatures included. A
     * device signs its own [RosterDevice.caps] at will, so without a limit one device could
     * grow the roster past what a ROSTER message holds, and its revocation would never
     * travel. A roster of [Roster.MAX_DEVICES] records takes at most 1 MiB.
     */
    const val MAX_RECORD_BYTES: Int = 4 * 1024

    /**
     * The most bytes of the canonical JSON of a revocation: its seal names at most
     * [Roster.MAX_DEVICES] identities, which take about 14 KiB.
     */
    const val MAX_REVOCATION_BYTES: Int = 16 * 1024

    /**
     * The deepest nesting of arrays and objects in a device record or a revocation, the
     * object itself included. A message nests a roster at most 3 levels deeper, as the
     * enrollment bundle does, so every roster stays within [EnvelopeCodec.MAX_DEPTH].
     */
    const val MAX_RECORD_DEPTH: Int = 8

    private val recordLimits = JsonLimits(maxDepth = MAX_RECORD_DEPTH, maxNodes = MAX_REVOCATION_BYTES)

    private const val FIRST_PRINTABLE_ASCII = ' '
    private const val LAST_PRINTABLE_ASCII = '~'

    /** The bidirectional controls: they reorder the text around them. */
    private val bidiControls: Set<Char> = setOf(
        '؜',
        '‎',
        '‏',
        '‪',
        '‫',
        '‬',
        '‭',
        '‮',
        '⁦',
        '⁧',
        '⁨',
        '⁩',
    )

    /**
     * `true` if [label] is a valid name of a device: 1 to [MAX_LABEL_LENGTH] Unicode code
     * points, none of them a control character (Cc), a line or paragraph separator (Zl, Zp),
     * a bidirectional control, or a lone surrogate. Emoji sequences, with their joiners and
     * variation selectors, are valid.
     */
    fun isValidLabel(label: String): Boolean {
        var codePoints = 0
        var index = 0
        while (index < label.length) {
            val char = label[index]
            val paired = char.isHighSurrogate() && label.getOrNull(index + 1)?.isLowSurrogate() == true
            if (!paired && !isAllowed(char)) return false
            codePoints++
            index += if (paired) 2 else 1
        }
        return codePoints in 1..MAX_LABEL_LENGTH
    }

    /** `true` if the canonical JSON of [device] takes at most [MAX_RECORD_BYTES], at most [MAX_RECORD_DEPTH] deep. */
    fun isWithinLimits(device: RosterDevice): Boolean = isWithinLimits(device.canonicalText, MAX_RECORD_BYTES)

    /**
     * `true` if the canonical JSON of [revocation] takes at most [MAX_REVOCATION_BYTES] and
     * nests at most [MAX_RECORD_DEPTH] deep, and its seal names at most [Roster.MAX_DEVICES]
     * identities.
     */
    fun isWithinLimits(revocation: Revocation): Boolean =
        revocation.admitted.size <= Roster.MAX_DEVICES && isWithinLimits(revocation.canonicalText, MAX_REVOCATION_BYTES)

    private fun isWithinLimits(canonicalText: String, maxBytes: Int): Boolean {
        val bytes = canonicalText.encodeToByteArray()
        return bytes.size <= maxBytes && recordLimits.admits(bytes)
    }

    /** `true` if [platform] is a valid platform name: up to [MAX_PLATFORM_LENGTH] printable ASCII characters. */
    fun isValidPlatform(platform: String): Boolean =
        platform.length <= MAX_PLATFORM_LENGTH && platform.all { it in FIRST_PRINTABLE_ASCII..LAST_PRINTABLE_ASCII }

    private fun isAllowed(char: Char): Boolean = when (char.category) {
        CharCategory.CONTROL,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.SURROGATE,
        -> false

        else -> char !in bidiControls
    }
}

/**
 * The removal of [deviceId] from the group, signed by the device [by] at [at] with its
 * authority key; a device without one may only leave, signed with its device key.
 * Revocations are sticky: a revoked device id never becomes active again.
 *
 * A revocation is kept as its [raw] object, which its signature covers, as a device record
 * is, see [RosterDevice.raw].
 *
 * A revoked device keeps its signing key and can stamp a record with any time, so the
 * revocation seals what the device did before it: of the devices it admitted, only the
 * ones in [admitted] count, see [com.artemchep.keyguard.feature.p2psync.roster.RosterActivity].
 *
 * @property admitted the identity hashes of the devices that [deviceId] admitted, as far as
 *   [by] knew when it signed, strictly sorted, see
 *   [com.artemchep.keyguard.feature.p2psync.roster.identityHash]: of a whole identity, so a
 *   new record with the key of a sealed device is never sealed.
 * @property sig the canonical base64url signature of [by] over the revocation.
 */
@Serializable(with = RevocationSerializer::class)
class Revocation private constructor(
    raw: JsonObject,
) : RawObject(raw) {
    private val fields: Fields = decodeFields(Fields.serializer(), raw)

    val deviceId: String get() = fields.deviceId

    val at: Hlc get() = fields.at

    val by: String get() = fields.by

    val admitted: List<String> get() = fields.admitted

    val sig: String get() = fields.sig

    /** Returns this revocation with the given fields; the others, also unknown ones, stay as they are. */
    fun copy(
        deviceId: String = this.deviceId,
        at: Hlc = this.at,
        by: String = this.by,
        admitted: List<String> = this.admitted,
        sig: String = this.sig,
    ): Revocation {
        val after = Fields(deviceId = deviceId, at = at, by = by, admitted = admitted, sig = sig)
        return Revocation(raw.withChanges(Fields.serializer(), fields, after))
    }

    override fun toString(): String = "Revocation($raw)"

    @Serializable
    private class Fields(
        val deviceId: String,
        val at: Hlc,
        val by: String,
        val admitted: List<String> = emptyList(),
        val sig: String = "",
    )

    companion object {
        /** Returns a new revocation of the given fields. */
        operator fun invoke(
            deviceId: String,
            at: Hlc,
            by: String,
            admitted: List<String> = emptyList(),
            sig: String = "",
        ): Revocation {
            val fields = Fields(deviceId = deviceId, at = at, by = by, admitted = admitted, sig = sig)
            return Revocation(encodeFields(Fields.serializer(), fields))
        }

        /**
         * Returns the revocation [raw], as received.
         *
         * @throws IllegalArgumentException if a field this build reads is malformed, or a
         *   number has no canonical form, see [CanonicalJson].
         */
        fun fromRaw(raw: JsonObject): Revocation = Revocation(raw)
    }
}

internal object RevocationSerializer : RawObjectSerializer<Revocation>(Revocation::fromRaw, Revocation::raw)

/**
 * The devices of a sync group: every admitted device and every revocation. The active
 * devices are derived, see [com.artemchep.keyguard.feature.p2psync.roster.activeDevices].
 *
 * @property founder the id of the device that created the group.
 */
@Serializable
data class Roster(
    val founder: String,
    val devices: List<RosterDevice> = emptyList(),
    val revoked: List<Revocation> = emptyList(),
) {
    /** Returns this roster with its lists in canonical order and without duplicates. */
    fun normalized(): Roster = copy(
        devices = devices.canonicalOrder(),
        revoked = revoked.distinct().sortedWith(revocationOrder),
    )

    /** Returns the lowercase hex [hasher] digest of the canonical JSON of this roster. */
    fun contentHash(hasher: (ByteArray) -> ByteArray): String =
        EnvelopeCodec.hashOf(CanonicalJson.encodeToByteArray(serializer(), normalized()), hasher)

    /** `true` if this roster fits a ROSTER message, see [MAX_BYTES]. */
    fun fitsMessage(): Boolean {
        val bytes = CanonicalJson.encodeToByteArray(serializer(), this)
        return bytes.size <= MAX_BYTES && messageLimits.admits(bytes)
    }

    companion object {
        /**
         * The most device records of a roster: 256. Revoked devices count too. A merge that
         * would hold more is refused whole, see
         * [com.artemchep.keyguard.feature.p2psync.roster.RosterValidator.merge].
         */
        const val MAX_DEVICES: Int = 256

        /**
         * The most canonical JSON bytes of a roster: a ROSTER message, less room for what a
         * message adds. Every record and revocation is small, but nothing bounds how many
         * revocations an authority key signs, so a merge that would take more is refused
         * whole, see [com.artemchep.keyguard.feature.p2psync.roster.RosterValidator.merge]:
         * a roster that does not fit would never travel again.
         */
        const val MAX_BYTES: Int = EnvelopeCodec.MAX_CONTENT_SIZE

        private val messageLimits = JsonLimits(
            maxDepth = EnvelopeCodec.MAX_DEPTH,
            maxNodes = EnvelopeCodec.MAX_CONTENT_NODES,
        )
    }
}

/**
 * Returns the distinct records sorted by id. Records of one id, which only a forger writes,
 * are sorted by their canonical text.
 */
private fun List<RosterDevice>.canonicalOrder(): List<RosterDevice> = distinct()
    .sortedWith(compareBy<RosterDevice> { it.id }.thenBy { it.canonicalText })

internal val revocationOrder: Comparator<Revocation> =
    compareBy<Revocation>({ it.deviceId }, { it.at }, { it.by }, { it.sig }, { it.canonicalText })
