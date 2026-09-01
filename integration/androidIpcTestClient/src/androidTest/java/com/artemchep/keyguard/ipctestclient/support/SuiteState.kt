package com.artemchep.keyguard.ipctestclient.support

import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import org.junit.Assume.assumeTrue
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * The fixture facts each test class needs, derived once per process.
 *
 * Registration and key selection persist in Keyguard across runs, so nothing
 * here may assume a prompt appears: every step approves only if the provider
 * asks. Values are memoised so a full run pays for them once, and every test
 * class still works when run alone.
 */
class SuiteState(private val rule: KeyguardProviderRule) {
    /** Registers this client if Keyguard does not know it yet. */
    fun ensureRegistered() {
        if (registered) return
        rule
            .openPgpRunner()
            .run(OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION))
            .requireOpenPgpSuccess()
        registered = true
    }

    /**
     * A signing key id, selected through the approval dialog the first time.
     *
     * Skips the calling test when the vault holds no signing-capable key: that
     * is a fixture gap, not a contract failure.
     */
    fun signKeyId(): Long {
        cachedSignKeyId?.let { return it }
        ensureRegistered()
        val exchange = rule
            .openPgpRunner()
            .run(OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID))
        val result = exchange.requireOpenPgpSuccess()
        val keyId = result.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L)
        assumeTrue("The vault holds no OpenPGP signing key.", keyId != 0L)
        cachedSignKeyId = keyId
        cachedPrimaryUserId = result.getStringExtra(OpenPgpApi.RESULT_PRIMARY_USER_ID)
        return keyId
    }

    fun primaryUserId(): String? {
        signKeyId()
        return cachedPrimaryUserId
    }

    /**
     * The e-mail of the signing key's primary user id.
     *
     * Skips the calling test when the user id carries no e-mail.
     */
    fun signingEmail(): String {
        val userId = primaryUserId().orEmpty()
        val email = userId.substringAfter('<', "").substringBefore('>').ifBlank { null }
            ?: userId.takeIf { it.contains('@') }
        assumeTrue("The signing key's primary user id has no e-mail: $userId", email != null)
        return email!!
    }

    /** Every encryption-capable key id the provider will admit for this client. */
    fun encryptionKeyIds(): List<Long> {
        cachedKeyIds?.let { return it }
        ensureRegistered()
        val exchange = rule
            .openPgpRunner(ApprovalRobot.Action.APPROVE_ALL_CANDIDATES)
            .run(OpenPgpRequestSpec(OpenPgpOperation.GET_KEY_IDS))
        val keyIds = exchange
            .requireOpenPgpSuccess()
            .getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)
            ?.toList()
            .orEmpty()
        assumeTrue("The vault holds no OpenPGP encryption key.", keyIds.isNotEmpty())
        cachedKeyIds = keyIds
        return keyIds
    }

    fun sshKeyId(): String {
        cachedSshKeyId?.let { return it }
        val exchange = rule
            .sshRunner()
            .run(SshRequestSpec(SshOperation.SELECT_KEY))
        val keyId = exchange
            .requireSshSuccess()
            .getStringExtra(SshAuthenticationApi.EXTRA_KEY_ID)
        assumeTrue("The vault holds no SSH key.", !keyId.isNullOrBlank())
        cachedSshKeyId = keyId
        return keyId!!
    }

    private companion object {
        @Volatile
        var registered = false

        @Volatile
        var cachedSignKeyId: Long? = null

        @Volatile
        var cachedPrimaryUserId: String? = null

        @Volatile
        var cachedKeyIds: List<Long>? = null

        @Volatile
        var cachedSshKeyId: String? = null
    }
}
