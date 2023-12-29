package com.artemchep.keyguard.util

import java.io.File

fun File.traverse(): Sequence<File> = list()
    .orEmpty()
    .asSequence()
    .map { path ->
        File(this, path)
    }
    .flatMap { file ->
        if (file.isDirectory) {
            val childFiles = file.traverse()
            childFiles
        } else {
            sequenceOf(file)
        }
    }
