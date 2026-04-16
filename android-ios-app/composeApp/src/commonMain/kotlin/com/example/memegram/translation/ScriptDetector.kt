package com.example.memegram.translation


object ScriptDetector {

    data class ScriptHint(
        val langCode: String?,
        val script: String,
        val confidence: Float
    )


    fun detect(text: String): ScriptHint? {
        if (text.isBlank()) return null

        var cyrillic = 0
        var latin = 0
        var cjk = 0
        var hangul = 0
        var kana = 0
        var arabic = 0
        var devanagari = 0
        var thai = 0
        var greek = 0
        var hebrew = 0
        var total = 0

        for (ch in text) {
            if (ch.isWhitespace() || ch.isDigit() || ch in ".,!?;:\"'()-–—…@#\$%^&*+=/<>[]{}|\\~`") continue
            total++
            val cp = ch.code
            when {
                cp in 0x0400..0x04FF || cp in 0x0500..0x052F -> cyrillic++
                cp in 0x0041..0x024F || cp in 0x1E00..0x1EFF -> latin++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x2F00..0x2FDF -> cjk++
                cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF -> hangul++
                cp in 0x3040..0x309F || cp in 0x30A0..0x30FF -> kana++
                cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> arabic++
                cp in 0x0900..0x097F -> devanagari++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 0x0370..0x03FF || cp in 0x1F00..0x1FFF -> greek++
                cp in 0x0590..0x05FF || cp in 0xFB1D..0xFB4F -> hebrew++
            }
        }

        if (total < 2) return null

        val scripts = listOf(
            "Cyrillic" to cyrillic,
            "Latin" to latin,
            "CJK" to cjk,
            "Hangul" to hangul,
            "Kana" to kana,
            "Arabic" to arabic,
            "Devanagari" to devanagari,
            "Thai" to thai,
            "Greek" to greek,
            "Hebrew" to hebrew
        )
        val (dominantScript, dominantCount) = scripts.maxByOrNull { it.second } ?: return null
        if (dominantCount == 0) return null

        val ratio = dominantCount.toFloat() / total
        if (ratio < 0.5f) return null

        return when (dominantScript) {
            "Cyrillic" -> detectCyrillicLanguage(text, ratio)
            "Latin" -> ScriptHint(langCode = null, script = "Latin", confidence = ratio)
            "CJK" -> detectCJKLanguage(text, cjk, kana, hangul, ratio)
            "Hangul" -> ScriptHint(langCode = "ko", script = "Hangul", confidence = ratio)
            "Kana" -> ScriptHint(langCode = "ja", script = "Kana", confidence = ratio)
            "Arabic" -> detectArabicScript(text, ratio)
            "Devanagari" -> ScriptHint(langCode = "hi", script = "Devanagari", confidence = ratio)
            "Thai" -> ScriptHint(langCode = "th", script = "Thai", confidence = ratio)
            "Greek" -> ScriptHint(langCode = "el", script = "Greek", confidence = ratio)
            "Hebrew" -> ScriptHint(langCode = "he", script = "Hebrew", confidence = ratio)
            else -> null
        }
    }

    // ── Cyrillic disambiguation ──────────────────────────────────

    // Tajik-specific Cyrillic characters: Ғғ Ӯӯ Ҳҳ Ҷҷ Ӣӣ Ҳ Ғ Ӯ Ӣ Ҷ
    private val TAJIK_MARKERS = setOf(
        '\u0492', '\u0493', // Ғ ғ
        '\u04EE', '\u04EF', // Ӯ ӯ
        '\u04B2', '\u04B3', // Ҳ ҳ
        '\u04B6', '\u04B7', // Ҷ ҷ
        '\u04E2', '\u04E3', // Ӣ ӣ
    )

    // Ukrainian-specific: Іі Її Єє Ґґ
    private val UKRAINIAN_MARKERS = setOf(
        '\u0406', '\u0456', // І і
        '\u0407', '\u0457', // Ї ї
        '\u0404', '\u0454', // Є є
        '\u0490', '\u0491', // Ґ ґ
    )

    // Belarusian: Ўў
    private val BELARUSIAN_MARKERS = setOf(
        '\u040E', '\u045E', // Ў ў
    )

    // Serbian Cyrillic: Ђђ Јј Љљ Њњ Ћћ Џџ
    private val SERBIAN_MARKERS = setOf(
        '\u0402', '\u0452', // Ђ ђ
        '\u0408', '\u0458', // Ј ј
        '\u0409', '\u0459', // Љ љ
        '\u040A', '\u045A', // Њ њ
        '\u040B', '\u045B', // Ћ ћ
        '\u040F', '\u045F', // Џ џ
    )

    // Bulgarian uses the same base alphabet as Russian but without Ёё and with some frequency differences.
    // Hard to distinguish from Russian by characters alone — we won't try.

    private val RUSSIAN_INDICATORS = setOf(
        '\u044B', '\u042B', // ы Ы
        '\u044D', '\u042D', // э Э
        '\u044A', '\u042A', // ъ Ъ
        '\u0449', '\u0429', // щ Щ
    )

    private fun detectCyrillicLanguage(text: String, confidence: Float): ScriptHint {
        var tajikScore = 0
        var ukrainianScore = 0
        var belarusianScore = 0
        var serbianScore = 0
        var russianScore = 0

        for (ch in text) {
            when (ch) {
                in TAJIK_MARKERS -> tajikScore++
                in UKRAINIAN_MARKERS -> ukrainianScore++
                in BELARUSIAN_MARKERS -> belarusianScore++
                in SERBIAN_MARKERS -> serbianScore++
                in RUSSIAN_INDICATORS -> russianScore++
            }
        }

        val scores = listOf(
            "tg" to tajikScore,
            "uk" to ukrainianScore,
            "be" to belarusianScore,
            "sr" to serbianScore,
            "ru" to russianScore
        )
        val (bestLang, bestScore) = scores.maxByOrNull { it.second }!!

        if (bestScore > 0) {
            return ScriptHint(
                langCode = bestLang,
                script = "Cyrillic",
                confidence = confidence
            )
        }

        return ScriptHint(
            langCode = "ru",
            script = "Cyrillic",
            confidence = confidence * 0.8f
        )
    }

    // ── CJK disambiguation ───────────────────────────────────────

    private fun detectCJKLanguage(text: String, cjk: Int, kana: Int, hangul: Int, confidence: Float): ScriptHint {
        if (kana > 0) return ScriptHint(langCode = "ja", script = "CJK+Kana", confidence = confidence)
        return ScriptHint(langCode = "zh", script = "CJK", confidence = confidence)
    }

    // ── Arabic script disambiguation ─────────────────────────────

    // Persian/Farsi specific characters: پ چ ژ گ
    private val PERSIAN_MARKERS = setOf(
        '\u067E', // پ
        '\u0686', // چ
        '\u0698', // ژ
        '\u06AF', // گ
    )

    // Urdu markers: ٹ ڈ ڑ ں ے
    private val URDU_MARKERS = setOf(
        '\u0679', // ٹ
        '\u0688', // ڈ
        '\u0691', // ڑ
        '\u06BA', // ں
        '\u06D2', // ے
    )

    private fun detectArabicScript(text: String, confidence: Float): ScriptHint {
        var persianScore = 0
        var urduScore = 0

        for (ch in text) {
            when (ch) {
                in PERSIAN_MARKERS -> persianScore++
                in URDU_MARKERS -> urduScore++
            }
        }

        return when {
            urduScore > persianScore && urduScore > 0 ->
                ScriptHint(langCode = "ur", script = "Arabic/Urdu", confidence = confidence)
            persianScore > 0 ->
                ScriptHint(langCode = "fa", script = "Arabic/Persian", confidence = confidence)
            else ->
                ScriptHint(langCode = "ar", script = "Arabic", confidence = confidence)
        }
    }
}
