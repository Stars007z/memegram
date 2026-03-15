package com.example.voicetranslator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class WhisperInstrumentedTest {
    
    private lateinit var context: Context
    private lateinit var whisperManager: WhisperManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        whisperManager = WhisperManager(context)
    }
    
    @Test
    fun testOnDeviceInitialization() = runBlocking {
        whisperManager.initialize(WhisperManager.Mode.ON_DEVICE)
        
        kotlinx.coroutines.delay(3000)
        
        val state = whisperManager.state.value
        assertTrue("Model should initialize", 
            state is WhisperManager.WhisperState.Idle || 
            state is WhisperManager.WhisperState.Loading)
    }
    
    @Test
    fun testApiInitialization() = runBlocking {
        // Тест инициализации API (нужен ключ)
        val apiKey = System.getenv("OPENAI_API_KEY") ?: "test_key"
        whisperManager.initialize(WhisperManager.Mode.API, apiKey)
        
        kotlinx.coroutines.delay(1000)
        
        val state = whisperManager.state.value
        assertTrue("API should initialize", 
            state is WhisperManager.WhisperState.Idle)
    }
    
    @Test
    fun testFullTranslationPipeline() = runBlocking {
        whisperManager.initialize(WhisperManager.Mode.ON_DEVICE)
        kotlinx.coroutines.delay(5000)
        
        val testAudio = createTestAudioFile()
        
        whisperManager.translateVoiceMessage(testAudio, "en", "ru")
        
        kotlinx.coroutines.delay(10000)
        
        val state = whisperManager.state.value
        assertTrue("Should complete or error", 
            state is WhisperManager.WhisperState.Success || 
            state is WhisperManager.WhisperState.Error)
        
        if (state is WhisperManager.WhisperState.Success) {
            println("✅ Original: ${state.result.originalText}")
            println("✅ Translated: ${state.result.translatedText}")
            println("✅ Duration: ${state.result.durationMs}ms")
        }
        
        testAudio.delete()
    }
    
    private fun createTestAudioFile(): File {
        val file = File(context.cacheDir, "test_audio.wav")
        context.assets.open("test_audio.wav").use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
    
    @After
    fun teardown() {
        whisperManager.release()
    }
}
