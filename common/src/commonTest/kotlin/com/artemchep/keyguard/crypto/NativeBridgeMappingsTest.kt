package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentOperation
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpComponentPolicy
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentRole
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpMetadataResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPolicyUse
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRenewalAuthorization
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
                policyRevision = 1,
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
    )
}
