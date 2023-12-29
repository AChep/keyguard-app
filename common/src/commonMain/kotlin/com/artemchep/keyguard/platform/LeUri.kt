package com.artemchep.keyguard.platform

import java.io.File

expect abstract class LeUri

expect fun leParseUri(uri: String): LeUri

expect fun leParseUri(file: File): LeUri
