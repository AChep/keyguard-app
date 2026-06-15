package com.artemchep.keyguard.platform

expect abstract class LeUri

expect fun leParseUri(uri: String): LeUri
