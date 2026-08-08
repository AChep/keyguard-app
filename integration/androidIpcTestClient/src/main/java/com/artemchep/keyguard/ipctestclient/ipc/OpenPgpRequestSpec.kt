package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent
import org.openintents.openpgp.util.OpenPgpApi

/**
 * Everything a client can put into one OpenPGP request, including the things a
 * well-behaved client would not.
 *
 * The boolean extras are tri-state on purpose: `null` omits the extra, which is
 * not the same as sending `false`. The provider defaults
 * [OpenPgpApi.EXTRA_ENABLE_COMPRESSION] to `true`, so "unset" and "false" are
 * different requests and produce different approval digests.
 */
@Suppress("LongParameterList")
data class OpenPgpRequestSpec(
    val operation: OpenPgpOperation,
    val apiVersion: Int? = OpenPgpOperation.DEFAULT_API_VERSION,
    val payload: ByteArray = EMPTY_PAYLOAD,
    val userIds: List<String> = emptyList(),
    val singleUserId: String? = null,
    val keyIds: List<Long> = emptyList(),
    val selectedKeyIds: List<Long> = emptyList(),
    val signKeyId: Long? = null,
    val preselectKeyId: Long? = null,
    val keyId: Long? = null,
    val originalFilename: String? = null,
    val asciiArmor: Boolean? = null,
    val enableCompression: Boolean? = null,
    val opportunistic: Boolean? = null,
    val detachedSignature: ByteArray? = null,
    // Deliberate protocol violations, for the rejection paths.
    val customHeaders: Boolean = false,
    val minimize: Boolean = false,
    val actionOverride: String? = null,
    val omitAction: Boolean = false,
    val omitInput: Boolean = false,
    val omitOutputPipe: Boolean = false,
    val outputPipeIdOverride: Int? = null,
) {
    /** The action actually sent, which [actionOverride] may make unresolvable. */
    val action: String? get() = if (omitAction) null else actionOverride ?: operation.action

    fun toIntent(): Intent {
        val intent = Intent()
        action?.let(intent::setAction)
        apiVersion?.let { intent.putExtra(OpenPgpApi.EXTRA_API_VERSION, it) }
        putRecipients(intent)
        putKeyIds(intent)
        putOptions(intent)
        detachedSignature?.let {
            intent.putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, it)
        }
        if (customHeaders) {
            intent.putExtra(OpenPgpApi.EXTRA_CUSTOM_HEADERS, arrayOf("X-Test: 1"))
        }
        if (minimize) {
            intent.putExtra(OpenPgpApi.EXTRA_MINIMIZE, true)
        }
        return intent
    }

    private fun putRecipients(intent: Intent) {
        if (userIds.isNotEmpty()) {
            intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, userIds.toTypedArray())
        }
        singleUserId?.let { intent.putExtra(OpenPgpApi.EXTRA_USER_ID, it) }
    }

    private fun putKeyIds(intent: Intent) {
        if (keyIds.isNotEmpty()) {
            intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds.toLongArray())
        }
        if (selectedKeyIds.isNotEmpty()) {
            intent.putExtra(
                OpenPgpApi.EXTRA_KEY_IDS_SELECTED,
                selectedKeyIds.toLongArray(),
            )
        }
        signKeyId?.let { intent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, it) }
        preselectKeyId?.let { intent.putExtra(OpenPgpApi.EXTRA_PRESELECT_KEY_ID, it) }
        keyId?.let { intent.putExtra(OpenPgpApi.EXTRA_KEY_ID, it) }
    }

    private fun putOptions(intent: Intent) {
        originalFilename?.let {
            intent.putExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME, it)
        }
        asciiArmor?.let { intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, it) }
        enableCompression?.let {
            intent.putExtra(OpenPgpApi.EXTRA_ENABLE_COMPRESSION, it)
        }
        opportunistic?.let {
            intent.putExtra(OpenPgpApi.EXTRA_OPPORTUNISTIC_ENCRYPTION, it)
        }
    }

    /** The stream the provider should read, or null when none is sent. */
    fun input(): ByteArray? = payload.takeUnless { omitInput }

    fun outputMode(): OpenPgpOutputMode =
        if (omitOutputPipe) OpenPgpOutputMode.NONE else operation.outputMode

    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean = this === other || (
        other is OpenPgpRequestSpec &&
            operation == other.operation &&
            apiVersion == other.apiVersion &&
            payload.contentEquals(other.payload) &&
            userIds == other.userIds &&
            singleUserId == other.singleUserId &&
            keyIds == other.keyIds &&
            selectedKeyIds == other.selectedKeyIds &&
            signKeyId == other.signKeyId &&
            preselectKeyId == other.preselectKeyId &&
            keyId == other.keyId &&
            originalFilename == other.originalFilename &&
            asciiArmor == other.asciiArmor &&
            enableCompression == other.enableCompression &&
            opportunistic == other.opportunistic &&
            detachedSignature.contentEquals(other.detachedSignature) &&
            customHeaders == other.customHeaders &&
            minimize == other.minimize &&
            actionOverride == other.actionOverride &&
            omitAction == other.omitAction &&
            omitInput == other.omitInput &&
            omitOutputPipe == other.omitOutputPipe &&
            outputPipeIdOverride == other.outputPipeIdOverride
        )

    override fun hashCode(): Int {
        var result = operation.hashCode()
        result = HASH_FACTOR * result + apiVersion.hashCode()
        result = HASH_FACTOR * result + payload.contentHashCode()
        result = HASH_FACTOR * result + userIds.hashCode()
        result = HASH_FACTOR * result + keyIds.hashCode()
        result = HASH_FACTOR * result + signKeyId.hashCode()
        result = HASH_FACTOR * result + keyId.hashCode()
        result = HASH_FACTOR * result + detachedSignature.contentHashCode()
        return result
    }

    companion object {
        private const val HASH_FACTOR = 31
        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
