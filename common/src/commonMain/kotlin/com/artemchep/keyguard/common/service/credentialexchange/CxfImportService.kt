package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportResult
import kotlin.time.Instant

/**
 * Parses unencrypted FIDO Credential Exchange Format (CXF) v1.0 documents
 * received from another credential provider and plans the vault items to
 * create from them.
 *
 * The service is transport-agnostic and free of any platform dependencies —
 * the mirror image of [CxfExportService].
 */
interface CxfImportService {
    /**
     * Parses a CXF document [payload] into an import plan. The parsing is
     * lenient: unknown JSON members are ignored and unknown credential kinds
     * are counted as skipped. Only a malformed payload
     * ([ImportError.Parse]) or an unsupported document version
     * ([ImportError.UnsupportedVersion]) fails the parse.
     *
     * [now] stamps the planned create requests; it also becomes the creation
     * date of passkeys whose item carries no creation timestamp.
     */
    fun parse(
        payload: String,
        now: Instant,
    ): ImportResult
}
