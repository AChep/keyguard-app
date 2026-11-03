package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.feature.favicon.IconRequest

/**
 * Downloads the image of an icon, or emits `null` if the
 * icon must not or can not be downloaded.
 */
interface GetIconImage : (IconRequest) -> IO<ByteArray?>
