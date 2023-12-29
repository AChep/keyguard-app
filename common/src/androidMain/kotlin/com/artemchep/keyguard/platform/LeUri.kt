package com.artemchep.keyguard.platform

import android.net.Uri
import androidx.core.net.toUri
import java.io.File

actual typealias LeUri = Uri

actual fun leParseUri(uri: String): LeUri = Uri.parse(uri)

actual fun leParseUri(file: File): LeUri = file.toUri()
