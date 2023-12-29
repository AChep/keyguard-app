package com.artemchep.keyguard.platform

import java.io.File

actual abstract class LeUri

private class LeUriImpl() : LeUri()

actual fun leParseUri(uri: String): LeUri = LeUriImpl()

actual fun leParseUri(file: File): LeUri = LeUriImpl()
