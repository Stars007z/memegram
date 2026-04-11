package com.example.memegram

import app.cash.sqldelight.db.SqlDriver

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
expect fun createDatabaseDriver(): SqlDriver
expect fun getMlsDatabasePath(identity: String): String