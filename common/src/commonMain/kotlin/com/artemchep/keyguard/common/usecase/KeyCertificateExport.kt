package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/** Saves an OpenSSH certificate line as a `-cert.pub` file. */
interface KeyCertificateExport : (String) -> IO<String?>
