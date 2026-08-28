package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.authorizedAgentKeys
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentOperation
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpComponentPolicy
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentRole
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpMetadataResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPolicyUse
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRenewalAuthorization
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRevocationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeBridgeMappingsTest {
    @Test
    fun `new data policy authorizes raw signing while renewal stays separate`() {
        val renewalPrimary = component('1', NativeOpenPgpKeyComponentRole.PRIMARY)
        val templatePrimary = component('2', NativeOpenPgpKeyComponentRole.PRIMARY)
        val signingPrimary = component('3', NativeOpenPgpKeyComponentRole.PRIMARY)
        val templateSubkey = component('4', NativeOpenPgpKeyComponentRole.SUBKEY)
        val signingSubkey = component('5', NativeOpenPgpKeyComponentRole.SUBKEY)
        val encryptionSubkey =
            component(
                marker = '6',
                role = NativeOpenPgpKeyComponentRole.SUBKEY,
                operations = setOf(NativeOpenPgpAgentOperation.DECRYPT),
            )
        val nonSigningPrimary =
            component(
                marker = '7',
                role = NativeOpenPgpKeyComponentRole.PRIMARY,
                operations = setOf(NativeOpenPgpAgentOperation.DECRYPT),
            )
        val publicOnlySigningSubkey =
            component(
                marker = '8',
                role = NativeOpenPgpKeyComponentRole.SUBKEY,
                storedSecretMaterial = false,
            )
        val components =
            listOf(
                renewalPrimary,
                templatePrimary,
                signingPrimary,
                templateSubkey,
                signingSubkey,
                encryptionSubkey,
                nonSigningPrimary,
                publicOnlySigningSubkey,
            )
        val resolution =
            NativeOpenPgpMetadataResolution(
                certificates =
                    listOf(
                        NativeOpenPgpCertificateResolution(
                            index =
                                NativeOpenPgpCertificateIndex(
                                    primaryFingerprint = renewalPrimary.fingerprint,
                                    components = components,
                                    legacyDesignatedRevokers = emptyList(),
                                ),
                            policy =
                                listOf(
                                    policy(
                                        renewalPrimary,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                    ),
                                    policy(
                                        templatePrimary,
                                        NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY,
                                    ),
                                    policy(
                                        signingPrimary,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                        setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
                                    ),
                                    policy(
                                        templateSubkey,
                                        NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY,
                                    ),
                                    policy(
                                        signingSubkey,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                        setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
                                    ),
                                    policy(
                                        encryptionSubkey,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                        setOf(NativeOpenPgpPolicyUse.ENCRYPT_NEW_DATA),
                                    ),
                                    policy(
                                        nonSigningPrimary,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                    ),
                                    policy(
                                        publicOnlySigningSubkey,
                                        NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                        setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
                                    ),
                                ),
                        ),
                    ),
                evaluatedAtEpochSeconds = 1_700_000_000L,
                policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
            )

        val authorization = resolution.toDomain().authorization
        val keys = authorization.keys.associateBy { it.fingerprint }

        assertFalse(keys.getValue(renewalPrimary.fingerprint).canSign)
        assertFalse(keys.getValue(templatePrimary.fingerprint).canSign)
        assertTrue(keys.getValue(signingPrimary.fingerprint).canSign)
        assertFalse(keys.getValue(templateSubkey.fingerprint).canSign)
        assertTrue(keys.getValue(signingSubkey.fingerprint).canSign)
        assertTrue(keys.getValue(encryptionSubkey.fingerprint).canDecrypt)
        assertFalse(keys.getValue(templatePrimary.fingerprint).canDecrypt)
        assertFalse(keys.getValue(nonSigningPrimary.fingerprint).canSign)
        assertFalse(keys.containsKey(publicOnlySigningSubkey.fingerprint))
        assertEquals(
            GpgRenewalAuthorization.AUTHENTICATED,
            authorization.renewals.getValue(renewalPrimary.fingerprint),
        )
        assertEquals(
            GpgRenewalAuthorization.TEMPLATE_ONLY,
            authorization.renewals.getValue(templatePrimary.fingerprint),
        )
    }

    @Test
    fun `revocation states are transient and missing or unsupported policy stays indeterminate`() {
        val primary = component('A', NativeOpenPgpKeyComponentRole.PRIMARY)
        val subkey = component('B', NativeOpenPgpKeyComponentRole.SUBKEY)
        for (revision in listOf(1, GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION, 3)) {
            for (status in NativeOpenPgpRevocationStatus.entries) {
                val resolution = NativeOpenPgpMetadataResolution(
                    certificates = listOf(
                        NativeOpenPgpCertificateResolution(
                            index = NativeOpenPgpCertificateIndex(
                                primaryFingerprint = primary.fingerprint,
                                components = listOf(primary, subkey),
                                legacyDesignatedRevokers = emptyList(),
                            ),
                            policy = listOf(
                                policy(
                                    primary,
                                    NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
                                    setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
                                ).copy(revocationStatus = status),
                            ),
                        ),
                    ),
                    evaluatedAtEpochSeconds = 1_700_000_000L,
                    policyRevision = revision,
                )
                val domain = resolution.toDomain()
                val expected = if (revision == GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION) {
                    status.toDomain()
                } else {
                    GpgRevocationStatus.INDETERMINATE
                }
                assertEquals(expected, domain.authorization.revocations[primary.fingerprint])
                assertEquals(GpgRevocationStatus.INDETERMINATE, domain.authorization.revocations[subkey.fingerprint])
                assertEquals(
                    expected == GpgRevocationStatus.NOT_REVOKED,
                    domain.authorizedAgentKeys.any { it.fingerprint == primary.fingerprint },
                )
                if (expected != GpgRevocationStatus.NOT_REVOKED) {
                    assertEquals(GpgRenewalAuthorization.NONE, domain.authorization.renewals[primary.fingerprint])
                }
            }
        }
    }

    @Test
    fun `agent authorization requires explicit non-revocation for its normalized fingerprint`() {
        val fingerprint = "A".repeat(40)
        val authorization = GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = 1_700_000_000L,
            policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
            keys = listOf(
                GpgAgentKeyMetadataKey(
                    keygrip = "B".repeat(40),
                    fingerprint = fingerprint.lowercase(),
                    capabilities = setOf("sign"),
                ),
            ),
        )
        assertTrue(authorization.authorizedAgentKeys.isEmpty())
        for (status in GpgRevocationStatus.entries) {
            val updated = authorization.copy(revocations = mapOf(fingerprint to status))
            assertEquals(status == GpgRevocationStatus.NOT_REVOKED, updated.authorizedAgentKeys.isNotEmpty())
        }
    }

    private fun component(
        marker: Char,
        role: NativeOpenPgpKeyComponentRole,
        operations: Set<NativeOpenPgpAgentOperation> = setOf(NativeOpenPgpAgentOperation.SIGN),
        storedSecretMaterial: Boolean = true,
    ) = NativeOpenPgpKeyComponentIndex(
        fingerprint = marker.toString().repeat(40),
        role = role,
        publicKeyAlgorithmId = 1,
        algorithm = "RSA",
        keygrips = listOf(marker.lowercaseChar().toString().repeat(40)),
        storedSecretMaterial = storedSecretMaterial,
        agentOperations = operations,
    )

    private fun policy(
        component: NativeOpenPgpKeyComponentIndex,
        renewal: NativeOpenPgpRenewalAuthorization,
        uses: Set<NativeOpenPgpPolicyUse> = emptySet(),
    ) = NativeOpenPgpComponentPolicy(
        fingerprint = component.fingerprint,
        allowedNewDataUses = uses,
        renewal = renewal,
        revocationStatus = NativeOpenPgpRevocationStatus.NOT_REVOKED,
    )
}
