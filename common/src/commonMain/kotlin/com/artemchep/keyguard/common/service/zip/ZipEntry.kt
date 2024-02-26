package com.artemchep.keyguard.common.service.zip

import java.io.InputStream

class ZipEntry(
    val name: String,
    val stream: () -> InputStream,
) {
}
