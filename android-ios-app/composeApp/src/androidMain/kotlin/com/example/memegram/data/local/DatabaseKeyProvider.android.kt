package com.example.memegram.data.local

import com.example.memegram.AppContextHolder
import java.util.UUID
import androidx.core.content.edit

actual fun getDatabasePassphrase(): String {
    val sharedPrefs = SecurePrefsFactory.create(
        AppContextHolder.context,
        "db_secure_prefs",
    )

    var passphrase = sharedPrefs.getString("sql_cipher_passphrase", null)
    if (passphrase == null) {
        passphrase = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        sharedPrefs.edit { putString("sql_cipher_passphrase", passphrase) }
    }

    return passphrase
}
