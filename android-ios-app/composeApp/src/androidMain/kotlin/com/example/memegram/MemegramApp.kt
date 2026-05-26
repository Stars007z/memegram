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

        createMessagesNotificationChannels()
    }

    private fun createMessagesNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)

        val baseName = if (isRu) "Сообщения" else "Messages"
        val baseDesc = if (isRu) {
            "Уведомления о новых сообщениях в чатах"
        } else {
            "Notifications about new chat messages"
        }

        for (strength in 0..3) {
            val channelId = channelIdForVibration(strength)
            if (nm.getNotificationChannel(channelId) != null) continue

            val suffix = when (strength) {
                0 -> if (isRu) " · без вибрации" else " · no vibration"
                1 -> if (isRu) " · слабая" else " · light"
                2 -> if (isRu) " · обычная" else " · normal"
                else -> if (isRu) " · сильная" else " · strong"
            }

            val importance = if (strength == 0) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_HIGH
            }

            val channel = NotificationChannel(
                channelId,
                baseName + suffix,
                importance,
            ).apply {
                this.description = baseDesc
                enableLights(strength > 0)
                setShowBadge(true)
                if (strength == 0) {
                    setSound(null, null)
                    enableVibration(false)
                } else {
                    enableVibration(true)
                    vibrationPattern = vibrationPatternFor(strength)
                }
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_MESSAGES_PREFIX = "memegram_messages_v2_strength_"

        fun channelIdForVibration(strength: Int): String {
            val s = strength.coerceIn(0, 3)
            return "$CHANNEL_MESSAGES_PREFIX$s"
        }

        fun vibrationPatternFor(strength: Int): LongArray = when (strength.coerceIn(0, 3)) {
            0 -> longArrayOf(0L)
            1 -> longArrayOf(0L, 50L)
            2 -> longArrayOf(0L, 150L)
            else -> longArrayOf(0L, 250L, 150L, 250L)
        }
    }
}
