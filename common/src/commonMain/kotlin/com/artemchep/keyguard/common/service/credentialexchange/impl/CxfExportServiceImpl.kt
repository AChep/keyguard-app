package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.CxfAccountResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportService
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfDocument
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfVersion
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Exporter
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.platform.recordException
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * The mapping half of [CxfExportService].
 *
 * An interface rather than the concrete [CxfSecretMapper] so the totality guard
 * in [CxfExportServiceImpl.buildAccountResult] can be driven by a mapper that
 * raises: `mapSshKey` already absorbs everything the injected
 * [SshKeyPkcs8Exporter] can throw.
 */
internal interface CxfAccountMapper {
    fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult
}

class CxfExportServiceImpl internal constructor(
    private val mapper: CxfAccountMapper,
) : CxfExportService {
    constructor(
        directDI: DirectDI,
    ) : this(
        passkeyCrypto = directDI.instance(),
        sshKeyPkcs8Exporter = directDI.instance(),
    )

    constructor(
        passkeyCrypto: PasskeyCrypto,
        sshKeyPkcs8Exporter: SshKeyPkcs8Exporter,
    ) : this(
        mapper = CxfSecretMapper(
            passkeyCrypto = passkeyCrypto,
            sshKeyPkcs8Exporter = sshKeyPkcs8Exporter,
        ),
    )

    constructor(
        sshKeyPkcs8Exporter: SshKeyPkcs8Exporter,
    ) : this(
        passkeyCrypto = NativePasskeyCrypto,
        sshKeyPkcs8Exporter = sshKeyPkcs8Exporter,
    )

    /**
     * A dedicated [Json] instance that pins the CXF wire format, separate from
     * the DI-provided instance whose configuration is tuned for the Bitwarden
     * sync. Null fields are omitted; the polymorphic `type` discriminator comes
     * from the [@JsonClassDiscriminator][kotlinx.serialization.json.JsonClassDiscriminator]
     * annotation on the credential hierarchy.
     *
     * `encodeDefaults` stays `false`, so CXF v1.0 §2.1.2 array encoding maps
     * onto the models as follows: required arrays (e.g. [CxfAccount.collections],
     * [CxfCollection.items][com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection.items])
     * MUST NOT declare default values or they would vanish from the payload
     * when empty, while optional arrays (e.g. `tags`, `subCollections`) use a
     * `null` default so that they are omitted when absent.
     */
    private val json = Json {
        explicitNulls = false
    }

    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult = runCatchingNonFatal {
        mapper.buildAccountResult(
            profile = profile,
            ciphers = ciphers,
            allowedTypes = allowedTypes,
            folders = folders,
        )
    }.getOrElse { e ->
        // The mirror of CxfImportServiceImpl.parse: this boundary is total. The
        // mapping reaches the native crypto seam and walks a user-built folder
        // tree, neither of which is guaranteed total, and a throw here would
        // take every *other* account down with it. The account is lost, so it
        // is counted.
        recordException(e)
        CxfAccountResult(
            account = null,
            skips = cxfExportSkips(CxfExportSkipReason.Account to 1),
        )
    }

    override fun buildDocument(
        accounts: List<CxfAccount>,
        exporterRpId: String,
        exporterDisplayName: String,
        timestamp: Instant,
    ): CxfDocument = CxfDocument(
        version = CxfVersion.CURRENT,
        exporterRpId = exporterRpId,
        exporterDisplayName = exporterDisplayName,
        timestamp = timestamp.epochSeconds,
        accounts = accounts,
    )

    override fun encode(
        document: CxfDocument,
    ): String = json.encodeToString(document)
}
