package com.example.memegram.data.wipe

import android.content.Context
import com.example.memegram.AppContextHolder
import com.example.memegram.database.AppDatabase
import com.example.memegram.data.local.SecurePrefsFactory
import com.example.memegram.getHardwareDeviceId
import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.edit

private val FILES_SUBDIRS_TO_CLEAR = listOf(
    "translation_models",
)

private val CACHE_SUBDIRS_TO_CLEAR = listOf(
    "avatars",
)

actual class ClientDataWiper(
    private val context: Context,
    private val plainSettings: Settings,
    private val secureSettings: Settings,
    private val mlsManager: MlsManager,
    private val database: AppDatabase,
) {
    actual suspend fun wipeAll() {
        withContext(Dispatchers.IO) {
            val retainedDeviceId = secureSettings.getStringOrNull("device_id")
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { getHardwareDeviceId() }.getOrNull()

            clearAuthSecrets(retainedDeviceId)

            runCatching {
                database.appDatabaseQueries.transaction {
                    database.appDatabaseQueries.deleteAllMessages()
                    database.appDatabaseQueries.deleteAllChats()
                    database.appDatabaseQueries.clearBlockedUsers()
                    database.appDatabaseQueries.clearUserProfiles()
                }
                database.appDatabaseQueries.vacuumDb()
                println("MemegramDebug [AccountDelete] wipe.db.sql.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.db.sql.fail: ${it.message}") }

            runCatching {
                context.filesDir.listFiles { f ->
                    f.isFile && f.name.startsWith("mls_") && (
                        f.name.endsWith(".sqlite") ||
                            f.name.endsWith(".sqlite-journal") ||
                            f.name.endsWith(".sqlite-wal") ||
                            f.name.endsWith(".sqlite-shm")
                    )
                }?.forEach { it.delete() }
                println("MemegramDebug [AccountDelete] wipe.mls.files.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.mls.files.fail: ${it.message}") }

            runCatching {
                mlsManager.clearAll()
                println("MemegramDebug [AccountDelete] wipe.mls.manager.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.mls.manager.fail: ${it.message}") }

            runCatching {
                plainSettings.clear()
                println("MemegramDebug [AccountDelete] wipe.prefs.plain.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.prefs.plain.fail: ${it.message}") }

            runCatching {
                secureSettings.clear()
                println("MemegramDebug [AccountDelete] wipe.prefs.secure.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.prefs.secure.fail: ${it.message}") }

            runCatching {
                SecurePrefsFactory.create(context, "key_manager_secure_prefs")
                    .edit(commit = true) { clear() }
                println("MemegramDebug [AccountDelete] wipe.identity.keys.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.identity.keys.fail: ${it.message}") }

            runCatching {
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                println("MemegramDebug [AccountDelete] wipe.cache.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.cache.fail: ${it.message}") }

            FILES_SUBDIRS_TO_CLEAR.forEach { sub ->
                runCatching {
                    File(context.filesDir, sub).deleteRecursively()
                }.onFailure { println("MemegramDebug [AccountDelete] wipe.files[$sub].fail: ${it.message}") }
            }
            CACHE_SUBDIRS_TO_CLEAR.forEach { sub ->
                runCatching { File(context.cacheDir, sub).deleteRecursively() }
                    .onFailure { println("MemegramDebug [AccountDelete] wipe.cache[$sub].fail: ${it.message}") }
            }

            runCatching {
                context.filesDir.listFiles { f ->
                    f.isFile && f.name.startsWith("voice_") && f.name.endsWith(".m4a")
                }?.forEach { it.delete() }
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.voice.fail: ${it.message}") }

            restoreDeviceId(retainedDeviceId)

            println("MemegramDebug [AccountDelete] wipeAll: done")
        }
    }

    private fun clearAuthSecrets(retainedDeviceId: String?) {
        runCatching {
            secureSettings.remove("access_token")
            secureSettings.remove("refresh_token")
            secureSettings.remove("user_id")
            secureSettings.remove("device_type")
            secureSettings.remove("expires_at")
            retainedDeviceId?.let { secureSettings.putString("device_id", it) }
            println("MemegramDebug [AccountDelete] wipe.session.immediate.ok")
        }.onFailure { println("MemegramDebug [AccountDelete] wipe.session.immediate.fail: ${it.message}") }
    }

    private fun restoreDeviceId(retainedDeviceId: String?) {
        if (retainedDeviceId.isNullOrBlank()) return
        runCatching {
            secureSettings.putString("device_id", retainedDeviceId)
            println("MemegramDebug [AccountDelete] wipe.device_id.restored")
        }.onFailure { println("MemegramDebug [AccountDelete] wipe.device_id.restore.fail: ${it.message}") }
    }
}

actual fun createClientDataWiper(
    plainSettings: Settings,
    secureSettings: Settings,
    mlsManager: MlsManager,
    database: AppDatabase,
): ClientDataWiper = ClientDataWiper(
    context = AppContextHolder.context,
    plainSettings = plainSettings,
    secureSettings = secureSettings,
    mlsManager = mlsManager,
    database = database,
)
