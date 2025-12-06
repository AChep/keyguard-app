package com.artemchep.keyguard.common.service.database

import app.cash.sqldelight.db.SqlDriver

interface DatabaseSqlHelper<Database> : DatabaseChangePassword {
    val driver: SqlDriver

    val database: Database
}
