package org.redundent.kotlin.xml

import java.io.InputStream

fun parse(inputStream: InputStream): Node = parse(inputStream.readBytes())
