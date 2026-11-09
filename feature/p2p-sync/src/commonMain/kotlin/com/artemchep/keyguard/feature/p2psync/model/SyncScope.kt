package com.artemchep.keyguard.feature.p2psync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a sync group syncs. Each group has one scope, which names the kinds its devices read,
 * see [EntryKind.scope]; it keeps and relays entries of any other kind without reading them.
 * A pairing code and an enrollment of one scope never complete with a device of another.
 *
 * The [SerialName] of a scope names it in the enrollment HELLO and in storage, see
 * [wireName], and its [codeId] in pairing codes; neither ever changes.
 *
 * @property codeId the number of the scope in pairing codes, see [PairingCode].
 * @property sasInfo the HKDF info prefix of the short authentication string, so two devices
 *   that pair into different scopes never see the same digits.
 * @property roomLabel the label of the scope in what a group derives for the relay: the
 *   room of each pair of its devices, and the keys of its blob items.
 */
@Serializable
enum class SyncScope(
    val codeId: Int,
    val sasInfo: String,
    val roomLabel: String,
) {
    /** The records of the app itself, such as the email forwarders, shared by linked apps. */
    @SerialName("app")
    APP(
        codeId = 0,
        sasInfo = "keyguard-sas/app/v1",
        roomLabel = "keyguard-app-room",
    ),
    // Reserved: "vault", 1, for the vaults.
    ;

    /** The name of the scope in pairing codes and in storage. */
    val wireName: String get() = serializer().descriptor.getElementName(ordinal)

    companion object {
        private val byWireName by lazy { entries.associateBy { it.wireName } }

        private val byCodeId by lazy { entries.associateBy { it.codeId } }

        /** Returns the scope named [wireName], or `null` if this build does not know it. */
        fun ofWireName(wireName: String): SyncScope? = byWireName[wireName]

        /** Returns the scope of the pairing code number [codeId], or `null` if this build does not know it. */
        fun ofCodeId(codeId: Int): SyncScope? = byCodeId[codeId]
    }
}
