package com.example.memegram

import android.os.Build
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.example.memegram.database.AppDatabase
import com.example.memegram.data.local.getDatabasePassphrase
import java.io.File // <-- ДОБАВИТЬ

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getMlsDatabasePath(identity: String): String {
    val context = AppContextHolder.context
    return File(context.filesDir, "mls_${identity}.sqlite").absolutePath
}

actual fun createDatabaseDriver(): SqlDriver {
    System.loadLibrary("sqlcipher")
    val passphrase = getDatabasePassphrase()
    val factory = SupportOpenHelperFactory(passphrase.toByteArray())
    return AndroidSqliteDriver(
        schema = AppDatabase.Schema,
        context = AppContextHolder.context,
        name = "memegram.db",
        factory = factory
    )
}