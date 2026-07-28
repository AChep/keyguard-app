package app.keemobile.kotpass.models

import java.io.ByteArrayInputStream
import java.io.InputStream

fun BinaryData.inputStream(): InputStream = ByteArrayInputStream(getContent())
