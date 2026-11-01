package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import kotlinx.io.Source

/**
 * A picked file to import. [accessToken] is the platform's access material for
 * [uri] — a security-scoped bookmark on iOS — and goes along with every read.
 */
class FileImportInput(
    private val fileService: FileService,
    private val uri: String,
    private val accessToken: FileAccessToken?,
    override val name: String?,
) : ImportInput {
    override fun open(): Source = fileService.readFromFile(
        uri = uri,
        accessToken = accessToken,
    )
}
