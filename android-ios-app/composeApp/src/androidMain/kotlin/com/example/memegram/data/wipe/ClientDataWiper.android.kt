package com.example.memegram.data.wipe

import android.content.Context
import com.example.memegram.AppContextHolder
import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.edit

private val PREFS_FILES = listOf(
    "session_secure_prefs",
    "key_manager_secure_prefs",
    "db_secure_prefs",
)

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
) {
    actual suspend fun wipeAll() {
        withContext(Dispatchers.IO) {
            runCatching {
                val deleted = context.deleteDatabase("memegram.db")
                println("MemegramDebug [AccountDelete] wipe.db.memegram=$deleted")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.db.fail: ${it.message}") }

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
                val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (sharedPrefsDir.exists()) {
                    sharedPrefsDir.listFiles()?.forEach { file ->
                        val name = file.name.removeSuffix(".xml")
                        runCatching {
                            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                            sp.edit(commit = true) { clear() }
                        }
                        runCatching { file.delete() }
                    }
                }
                println("MemegramDebug [AccountDelete] wipe.prefs.all.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.prefs.all.fail: ${it.message}") }

            PREFS_FILES.forEach { name ->
                runCatching {
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                }.onFailure { println("MemegramDebug [AccountDelete] wipe.pref[$name].fail: ${it.message}") }
            }

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

            println("MemegramDebug [AccountDelete] wipeAll: done")
        }
    }
}

actual fun createClientDataWiper(
    plainSettings: Settings,
    secureSettings: Settings,
    mlsManager: MlsManager,
): ClientDataWiper = ClientDataWiper(
    context = AppContextHolder.context,
    plainSettings = plainSettings,
    secureSettings = secureSettings,
    mlsManager = mlsManager,
)
