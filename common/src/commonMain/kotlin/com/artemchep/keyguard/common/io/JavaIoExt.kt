package com.artemchep.keyguard.common.io

import kotlinx.io.Sink
import kotlinx.io.Source
import java.io.InputStream
import java.io.OutputStream

expect fun Source.toInputStream(): InputStream

expect fun Sink.toOutputStream(): OutputStream
