package com.example.memegram

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.memegram.translation.NllbTranslationService
import com.example.memegram.translation.TranslationService
import org.koin.java.KoinJavaComponent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.context = this
        setContent {
            App()
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
}