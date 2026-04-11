package com.example.memegram

import platform.UIKit.UIDevice
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.example.memegram.database.AppDatabase
import com.example.memegram.data.local.getDatabasePassphrase

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun getMlsDatabasePath(identity: String): String {
    val documentDirectory = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    ).first() as String
    return "$documentDirectory/mls_${identity}.sqlite"
}

actual fun createDatabaseDriver(): SqlDriver {
    val passphrase = getDatabasePassphrase()
    val configuration = DatabaseConfiguration(
        name = "memegram.db",
        version = AppDatabase.Schema.version.toInt(),
        encryptionConfig = DatabaseConfiguration.Encryption(key = passphrase),
        create = { connection ->
            wrapConnection(connection) { AppDatabase.Schema.create(it) }
        },
        upgrade = { connection, oldVersion, newVersion ->
            wrapConnection(connection) { AppDatabase.Schema.migrate(it, oldVersion.toLong(), newVersion.toLong()) }
        }
    )
    return NativeSqliteDriver(configuration)
}