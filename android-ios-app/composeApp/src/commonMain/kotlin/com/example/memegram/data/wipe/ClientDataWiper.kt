package com.example.memegram.data.wipe

import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings

expect class ClientDataWiper {
    suspend fun wipeAll()
}

expect fun createClientDataWiper(
    plainSettings: Settings,
    secureSettings: Settings,
    mlsManager: MlsManager,
): ClientDataWiper

