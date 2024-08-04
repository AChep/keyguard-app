package com.artemchep.keyguard.common.service.export

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret

interface JsonExportService {
    /**
     * Exports given content into an extended Bitwarden
     * JSON export format.
     */
    fun export(
        organizations: List<DOrganization>,
        collections: List<DCollection>,
        folders: List<DFolder>,
        ciphers: List<DSecret>,
    ): String
}
