package com.artemchep.keyguard.feature.home.vault.search.query.parser

import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery

interface VaultSearchParser {
    fun parse(query: String): ParsedQuery
}
