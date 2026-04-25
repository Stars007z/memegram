package com.example.memegram.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

internal object SecurePrefsFactory {

    fun create(context: Context, fileName: String): SharedPreferences {
        return runCatching { build(context, fileName) }
            .getOrElse { first ->
                wipeUnderlyingFiles(context, fileName)
                runCatching { build(context, fileName) }.getOrElse {
                    throw first
                }
            }
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun wipeUnderlyingFiles(context: Context, fileName: String) {
        runCatching { context.deleteSharedPreferences(fileName) }
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (sharedPrefsDir.isDirectory) {
            sharedPrefsDir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.startsWith("__androidx_security_crypto_") && name.contains(fileName)) {
                    runCatching { f.delete() }
                }
            }
        }
    }
}
