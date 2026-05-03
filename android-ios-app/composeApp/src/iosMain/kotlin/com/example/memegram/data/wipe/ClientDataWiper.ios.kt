@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.memegram.data.wipe

import com.example.memegram.database.AppDatabase
import com.example.memegram.getHardwareDeviceId
import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Security.SecItemDelete
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecClassInternetPassword
import platform.Security.kSecClassCertificate
import platform.Security.kSecClassKey
import platform.Security.kSecClassIdentity

private val KEYCHAIN_SERVICES = listOf(
    "com.example.memegram.session",
    "com.example.memegram.keys",
    "com.example.memegram.database",
)

private val KEYCHAIN_CLASSES = listOf(
    kSecClassGenericPassword,
    kSecClassInternetPassword,
    kSecClassCertificate,
    kSecClassKey,
    kSecClassIdentity,
)

actual class ClientDataWiper(
    private val plainSettings: Settings,
    private val secureSettings: Settings,
    private val mlsManager: MlsManager,
    private val database: AppDatabase,
) {
    actual suspend fun wipeAll() {
        withContext(Dispatchers.Default) {
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
                val docs = nsPath(NSDocumentDirectory)
                if (docs != null) {
                    val fm = NSFileManager.defaultManager
                    val items = fm.contentsOfDirectoryAtPath(docs, error = null) ?: emptyList<Any>()
                    for (any in items) {
                        val name = any as? String ?: continue
                        val lower = name.lowercase()
                        val isAppDb = lower.startsWith("memegram.db") ||
                            (lower.startsWith("mls_") && (
                                lower.endsWith(".sqlite") ||
                                    lower.endsWith(".sqlite-journal") ||
                                    lower.endsWith(".sqlite-wal") ||
                                    lower.endsWith(".sqlite-shm")
                                ))
                        if (isAppDb) {
                            fm.removeItemAtPath("$docs/$name", error = null)
                        }
                    }
                }
                println("MemegramDebug [AccountDelete] wipe.db.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.db.fail: ${it.message}") }

            runCatching {
                mlsManager.clearAll()
                println("MemegramDebug [AccountDelete] wipe.mls.manager.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.mls.manager.fail: ${it.message}") }

            KEYCHAIN_SERVICES.forEach { service ->
                KEYCHAIN_CLASSES.forEach { klass ->
                    runCatching {
                        val query = mapOf<Any?, Any?>(
                            kSecClass to klass,
                            "svce" to service, // kSecAttrService
                        )
                        val ref = CFBridgingRetain(query) as CFDictionaryRef?
                        try {
                            SecItemDelete(ref)
                        } finally {
                            if (ref != null) CFRelease(ref)
                        }
                    }
                }
            }
            KEYCHAIN_CLASSES.forEach { klass ->
                runCatching {
                    val query = mapOf<Any?, Any?>(kSecClass to klass)
                    val ref = CFBridgingRetain(query) as CFDictionaryRef?
                    try {
                        SecItemDelete(ref)
                    } finally {
                        if (ref != null) CFRelease(ref)
                    }
                }
            }
            println("MemegramDebug [AccountDelete] wipe.keychain.ok")

            runCatching {
                val bundleId = NSBundle.mainBundle.bundleIdentifier
                if (bundleId != null) {
                    NSUserDefaults.standardUserDefaults.removePersistentDomainForName(bundleId)
                }
                NSUserDefaults.standardUserDefaults.synchronize()
                println("MemegramDebug [AccountDelete] wipe.userDefaults.ok")
            }.onFailure { println("MemegramDebug [AccountDelete] wipe.userDefaults.fail: ${it.message}") }

            runCatching { plainSettings.clear() }
                .onFailure { println("MemegramDebug [AccountDelete] wipe.settings.plain.fail: ${it.message}") }
            runCatching { secureSettings.clear() }
                .onFailure { println("MemegramDebug [AccountDelete] wipe.settings.secure.fail: ${it.message}") }

            wipeDirectory(NSDocumentDirectory, keepRootDirs = false)
            wipeDirectory(NSCachesDirectory, keepRootDirs = false)

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

    private fun wipeDirectory(directory: ULong, keepRootDirs: Boolean) {
        runCatching {
            val base = nsPath(directory) ?: return@runCatching
            val fm = NSFileManager.defaultManager
            val items = fm.contentsOfDirectoryAtPath(base, error = null) ?: return@runCatching
            for (any in items) {
                val name = any as? String ?: continue
                val full = "$base/$name"
                if (keepRootDirs) {
                    val attrs = fm.attributesOfItemAtPath(full, error = null)
                    val isDir = (attrs?.get("NSFileType") as? String) == "NSFileTypeDirectory"
                    if (!isDir) fm.removeItemAtPath(full, error = null)
                } else {
                    fm.removeItemAtPath(full, error = null)
                }
            }
        }.onFailure { println("MemegramDebug [AccountDelete] wipe.dir.fail: ${it.message}") }
    }

    private fun nsPath(directory: ULong): String? {
        val arr = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true)
        return arr.firstOrNull() as? String
    }
}

actual fun createClientDataWiper(
    plainSettings: Settings,
    secureSettings: Settings,
    mlsManager: MlsManager,
    database: AppDatabase,
): ClientDataWiper = ClientDataWiper(
    plainSettings = plainSettings,
    secureSettings = secureSettings,
    mlsManager = mlsManager,
    database = database,
)
