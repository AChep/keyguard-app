package com.artemchep.keyguard.common.service.wordlist

import com.artemchep.keyguard.common.io.IO

interface WordlistService {
    fun get(): IO<List<String>>
}
