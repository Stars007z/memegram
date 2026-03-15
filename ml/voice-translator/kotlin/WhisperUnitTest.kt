package com.example.voicetranslator

import org.junit.Test
import org.junit.Assert.*

class WhisperUnitTest {
    
    @Test
    fun testTranslationResult() {
        val result = TranslationResult(
            originalText = "Hello world",
            translatedText = "Привет мир",
            language = "en",
            durationMs = 1500,
            mode = TranslationResult.Mode.ON_DEVICE,
            success = true
        )
        
        assertEquals("Hello world", result.originalText)
        assertEquals("Привет мир", result.translatedText)
        assertTrue(result.success)
    }
    
    @Test
    fun testModeEnum() {
        val modes = TranslationResult.Mode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(TranslationResult.Mode.ON_DEVICE))
        assertTrue(modes.contains(TranslationResult.Mode.API))
    }
}
