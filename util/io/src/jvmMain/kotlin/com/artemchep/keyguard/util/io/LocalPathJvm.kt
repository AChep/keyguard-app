package com.artemchep.keyguard.util.io

import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString

fun LocalPath.toJavaFile(): File = File(value)

fun LocalPath.toNioPath(): Path = Path.of(value)

fun File.toLocalPath(): LocalPath = LocalPath(absoluteFile.path)

fun Path.toLocalPath(): LocalPath = LocalPath(toAbsolutePath().absolutePathString())
