package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfDocument
import kotlin.time.Instant

/**
 * Builds unencrypted FIDO Credential Exchange Format (CXF) v1.0 documents from
 * Keyguard vault data. Transport-agnostic and free of platform dependencies.
 */
interface CxfExportService {
    /**
     * Maps the given [profile], its [ciphers] and the [folders] belonging to it
     * into a single CXF account, emitting only the credential kinds in
     * [allowedTypes].
     *
     * The set is an exact filter: an empty set exports nothing, and an
     * unfiltered export must be requested with [CxfCredentialType.ALL]. Callers
     * build the set with [CxfCredentialType.parseAll], which drops values this
     * app does not recognize (CXP §3.2, "the Exporting Provider MUST ignore any
     * unknown values"), so an importer that asks only for kinds Keyguard cannot
     * emit parses down to nothing — and must receive nothing, never the whole
     * vault.
     *
     * The result also counts the credentials the CXF format cannot represent,
     * even when nothing is exportable and [CxfAccountResult.account] is `null`,
     * so the review screen can explain an empty export. The call is total: a
     * failure while mapping the account yields `account = null` plus a
     * [CxfExportSkipReason.Account] skip rather than a throw; only a
     * cancellation or a fatal error propagates.
     */
    fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder> = emptyList(),
    ): CxfAccountResult

    fun buildDocument(
        accounts: List<CxfAccount>,
        exporterRpId: String,
        exporterDisplayName: String,
        timestamp: Instant,
    ): CxfDocument

    fun encode(
        document: CxfDocument,
    ): String
}

/**
 * The outcome of building a single [CxfAccount], including counts of the
 * credentials that could not be exported.
 */
data class CxfAccountResult(
    /**
     * The built account, or `null` when the profile had nothing exportable.
     */
    val account: CxfAccount?,
    val skips: CxfExportSkips = cxfExportSkips(),
)
