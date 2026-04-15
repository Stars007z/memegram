package com.example.memegram.data.local

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.memegram.AppContextHolder
import java.util.UUID
import androidx.core.content.edit

actual fun getDatabasePassphrase(): String {
    val context = AppContextHolder.context

    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        "db_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var passphrase = sharedPrefs.getString("sql_cipher_passphrase", null)
    if (passphrase == null) {
        passphrase = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        sharedPrefs.edit { putString("sql_cipher_passphrase", passphrase) }
    }

    return passphrase
}