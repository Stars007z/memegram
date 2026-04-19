package com.example.memegram

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.memegram.push.MemegramFirebaseMessagingService
import com.example.memegram.push.PendingPushNavigation
import com.example.memegram.push.PushDeepLink
import com.example.memegram.translation.NllbTranslationService
import com.example.memegram.translation.TranslationService
import org.koin.java.KoinJavaComponent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContextHolder.context = this
        requestNotificationPermissionIfNeeded()
        handlePushIntent(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    private fun handlePushIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(MemegramFirebaseMessagingService.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotEmpty() } ?: return
        val chatName = intent.getStringExtra(MemegramFirebaseMessagingService.EXTRA_CHAT_NAME).orEmpty()
        val avatarMediaId = intent.getStringExtra(MemegramFirebaseMessagingService.EXTRA_AVATAR_MEDIA_ID).orEmpty()
        PushDeepLink.set(
            PendingPushNavigation(
                conversationId = convId,
                chatName = chatName,
                avatarMediaId = avatarMediaId,
            )
        )
        intent.removeExtra(MemegramFirebaseMessagingService.EXTRA_CONVERSATION_ID)
        intent.removeExtra(MemegramFirebaseMessagingService.EXTRA_CHAT_NAME)
        intent.removeExtra(MemegramFirebaseMessagingService.EXTRA_AVATAR_MEDIA_ID)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQ_POST_NOTIFICATIONS)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try {
                val service = KoinJavaComponent.get<TranslationService>(TranslationService::class.java)
                if (service is NllbTranslationService) {
                    service.releaseModel()
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1002
    }
}
