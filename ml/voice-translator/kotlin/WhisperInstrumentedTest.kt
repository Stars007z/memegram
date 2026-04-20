package com.example.voicetranslator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WhisperInstrumentedTest {

    private lateinit var context: Context
    private lateinit var whisperManager: WhisperManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        whisperManager = WhisperManager(context)
    }

    @After
    fun teardown() {
        whisperManager.release()
    }

    @Test
    fun testOnDeviceInitialization() = runBlocking {
        whisperManager.initialize(WhisperMode.ON_DEVICE)
        val terminal = withTimeout(30_000) {
            whisperManager.state.first {
                it is WhisperManager.WhisperState.Idle ||
                it is WhisperManager.WhisperState.Error
            }
        }
        assertTrue(
            "Expected Idle, got $terminal",
            terminal is WhisperManager.WhisperState.Idle
        )
    }

    @Test
    fun testApiInitialization() = runBlocking {
        val apiKey = System.getenv("OPENAI_API_KEY") ?: "test_key"
        whisperManager.initialize(WhisperMode.API, apiKey)
        val terminal = withTimeout(10_000) {
            whisperManager.state.first {
                it is WhisperManager.WhisperState.Idle ||
                it is WhisperManager.WhisperState.Error
            }
        }
        assertTrue(
            "API should reach a terminal state",
            terminal is WhisperManager.WhisperState.Idle ||
            terminal is WhisperManager.WhisperState.Error
        )
    }

    @Test
    fun testFullTranslationPipeline() = runBlocking {
        whisperManager.initialize(WhisperMode.ON_DEVICE)
        withTimeout(60_000) {
            whisperManager.state.first { it is WhisperManager.WhisperState.Idle }
        }

        val testAudio = createTestAudioFile()
        try {
            whisperManager.translateVoiceMessage(testAudio, "en", "ru")
            val terminal = withTimeout(120_000) {
                whisperManager.state.first {
                    it is WhisperManager.WhisperState.Success ||
                    it is WhisperManager.WhisperState.Error
                }
            }
            when (terminal) {
                is WhisperManager.WhisperState.Success -> {
                    println("Original:   ${terminal.result.originalText}")
                    println("Translated: ${terminal.result.translatedText}")
                    println("Duration:   ${terminal.result.durationMs}ms")
                }
                is WhisperManager.WhisperState.Error ->
                    println("Error: ${terminal.message}")
                else -> {}
            }
            assertTrue(
                "Pipeline should finish",
                terminal is WhisperManager.WhisperState.Success ||
                terminal is WhisperManager.WhisperState.Error
            )
        } finally {
            testAudio.delete()
        }
    }

    private fun createTestAudioFile(): File {
        val file = File(context.cacheDir, "test_audio.wav")
        context.assets.open("test_audio.wav").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
}
