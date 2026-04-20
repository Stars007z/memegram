package com.example.voicetranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperUnitTest {

    @Test
    fun testTranslationResult() {
        val result = TranslationResult(
            originalText = "Hello world",
            translatedText = "Привет мир",
            language = "en",
            durationMs = 1500,
            mode = WhisperMode.ON_DEVICE,
            success = true
        )

        assertEquals("Hello world", result.originalText)
        assertEquals("Привет мир", result.translatedText)
        assertEquals(WhisperMode.ON_DEVICE, result.mode)
        assertTrue(result.success)
    }

    @Test
    fun testModeEnum() {
        val modes = WhisperMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(WhisperMode.ON_DEVICE))
        assertTrue(modes.contains(WhisperMode.API))
    }
}
