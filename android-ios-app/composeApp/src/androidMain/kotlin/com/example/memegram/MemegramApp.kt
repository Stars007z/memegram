package com.example.memegram

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.memegram.di.appModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.util.Locale

class MemegramApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext

        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(appModule)
            }
        }

        createMessagesNotificationChannel()
    }

    private fun createMessagesNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) != null) return

        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)
        val name = if (isRu) "Сообщения" else "Messages"
        val description = if (isRu) {
            "Уведомления о новых сообщениях в чатах"
        } else {
            "Notifications about new chat messages"
        }

        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.description = description
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_MESSAGES = "memegram_messages"
    }
}
