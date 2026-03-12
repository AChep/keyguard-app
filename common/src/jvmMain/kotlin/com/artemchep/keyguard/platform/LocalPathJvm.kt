package com.artemchep.keyguard.platform

import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.absolutePathString

fun LocalPath.toJavaFile(): File = File(value)

fun LocalPath.toNioPath(): Path = Path.of(value)

fun LocalPath.toFileUriString(): String = toJavaFile().toURI().toString()

fun File.toLocalPath(): LocalPath = LocalPath(absoluteFile.path)

fun Path.toLocalPath(): LocalPath = LocalPath(toAbsolutePath().absolutePathString())

fun URI.toLocalPath(): LocalPath = Path.of(this).toLocalPath()
