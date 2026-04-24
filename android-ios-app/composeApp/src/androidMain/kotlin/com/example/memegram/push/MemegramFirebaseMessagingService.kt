package com.example.memegram.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.memegram.MainActivity
import com.example.memegram.MemegramApp
import com.example.memegram.R
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.data.repository.NotificationsRepository
import com.example.memegram.localization.AppStrings
import com.example.memegram.localization.EnStrings
import com.example.memegram.localization.RuStrings
import com.example.memegram.mls.MlsManager
import com.example.memegram.notifications.NotificationPrefs
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.util.Locale


class MemegramFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        println("MemegramDebug [FCM] onNewToken: ${token.take(16)}…")
        val repo = runCatching {
            GlobalContext.get().get<NotificationsRepository>()
        }.getOrNull() ?: return

        serviceScope.launch {
            repo.registerCurrentDeviceToken()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        println("MemegramDebug [FCM] onMessageReceived: from=${message.from}, data=$data")

        val eventType = data["event_type"].orEmpty()
        val conversationId = data["conversation_id"].orEmpty()
        if (conversationId.isEmpty()) {
            println("MemegramDebug [FCM] skip: empty conversation_id")
            return
        }

        when (eventType) {
            "new_message", "message_edited" -> showMessageNotification(data, conversationId)

            "member_added" -> {
                showSystemNotification(data, conversationId, openChatOnTap = true)
            }

            "member_kicked" -> {
                purgeConversationLocally(conversationId)
                showSystemNotification(data, conversationId, openChatOnTap = false)
            }

            "conversation_deleted" -> {
                purgeConversationLocally(conversationId)
            }

            else -> {
                println("MemegramDebug [FCM] silent event_type=$eventType")
            }
        }
    }

    private fun isChatMuted(conversationId: String): Boolean {
        val muteUntilMs = runCatching {
            val repo = GlobalContext.get().get<ChatRepository>()
            runBlocking { repo.getChatById(conversationId)?.muteUntil ?: 0L }
        }.getOrDefault(0L)
        return muteUntilMs > System.currentTimeMillis()
    }

    private fun notificationPrefs(): NotificationPrefs? = runCatching {
        GlobalContext.get().get<NotificationPrefs>()
    }.getOrNull()

    private fun appStrings(): AppStrings {
        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)
        return if (isRu) RuStrings else EnStrings
    }

    private fun showMessageNotification(
        data: Map<String, String>,
        conversationId: String,
    ) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) {
            println("MemegramDebug [FCM] notifications disabled by user")
            return
        }

        if (isChatMuted(conversationId)) {
            println("MemegramDebug [FCM] muted, skip message")
            return
        }

        val prefs = notificationPrefs()
        val previewEnabled = prefs?.previewEnabledNow() ?: NotificationPrefs.DEFAULT_PREVIEW_ENABLED
        val vibrationStrength =
            prefs?.vibrationStrengthNow() ?: NotificationPrefs.DEFAULT_VIBRATION_STRENGTH

        val title = data["title"].orEmpty().ifEmpty { data["conversation_name"].orEmpty() }
        val rawBody = data["body"].orEmpty()
        val body = if (previewEnabled) rawBody else appStrings().genericNotificationBody
        val chatName = data["conversation_name"].orEmpty().ifEmpty { title }
        val avatarMediaId = data["avatar_url"].orEmpty()

        val pi = buildOpenChatIntent(conversationId, chatName, avatarMediaId)

        val builder = baseNotificationBuilder(title, body, vibrationStrength)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setGroup("conv_$conversationId")

        if (previewEnabled && rawBody.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(rawBody))
        }

        try {
            nm.notify(conversationId.hashCode(), builder.build())
        } catch (se: SecurityException) {
            println("MemegramDebug [FCM] notify SecurityException: ${se.message}")
        }
    }

    private fun showSystemNotification(
        data: Map<String, String>,
        conversationId: String,
        openChatOnTap: Boolean,
    ) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return

        if (isChatMuted(conversationId)) {
            println("MemegramDebug [FCM] muted, skip system event")
            return
        }

        val title = data["title"].orEmpty().ifEmpty { data["conversation_name"].orEmpty() }
        val body = data["body"].orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        val chatName = data["conversation_name"].orEmpty().ifEmpty { title }
        val avatarMediaId = data["avatar_url"].orEmpty()

        val vibrationStrength =
            notificationPrefs()?.vibrationStrengthNow() ?: NotificationPrefs.DEFAULT_VIBRATION_STRENGTH

        val builder = baseNotificationBuilder(title, body, vibrationStrength)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        if (openChatOnTap) {
            builder.setContentIntent(buildOpenChatIntent(conversationId, chatName, avatarMediaId))
        } else {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    conversationId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }

        try {
            nm.notify(conversationId.hashCode(), builder.build())
        } catch (se: SecurityException) {
            println("MemegramDebug [FCM] notify SecurityException: ${se.message}")
        }
    }

    private fun baseNotificationBuilder(
        title: String,
        body: String,
        vibrationStrength: Int,
    ): NotificationCompat.Builder {
        val channelId = MemegramApp.channelIdForVibration(vibrationStrength)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (vibrationStrength <= 0) {
            builder.setVibrate(longArrayOf(0L))
        } else {
            builder.setVibrate(MemegramApp.vibrationPatternFor(vibrationStrength))
        }
        return builder
    }

    private fun buildOpenChatIntent(
        conversationId: String,
        chatName: String,
        avatarMediaId: String,
    ): PendingIntent {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CHAT_NAME, chatName)
            putExtra(EXTRA_AVATAR_MEDIA_ID, avatarMediaId)
        }
        return PendingIntent.getActivity(
            applicationContext,
            conversationId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun purgeConversationLocally(conversationId: String) {
        runCatching {
            val koin = GlobalContext.get()
            val chatRepository = koin.get<ChatRepository>()
            val mlsManager = runCatching { koin.get<MlsManager>() }.getOrNull()

            runBlocking {
                runCatching { chatRepository.deleteMessages(conversationId) }
                runCatching { chatRepository.deleteChat(conversationId) }
                if (mlsManager != null) {
                    runCatching { mlsManager.deleteLocalGroup(conversationId) }
                    runCatching { mlsManager.flushState() }
                }
            }
            println("MemegramDebug [FCM] purged local state for conv=$conversationId")
        }.onFailure {
            println("MemegramDebug [FCM] purgeConversationLocally failed: ${it.message}")
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "notification_conversation_id"
        const val EXTRA_CHAT_NAME = "notification_chat_name"
        const val EXTRA_AVATAR_MEDIA_ID = "notification_avatar_media_id"
    }
}
