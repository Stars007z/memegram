data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val language: String,
    val durationMs: Long,
    val mode: Mode,
    val success: Boolean,
    val error: String? = null
) {
    enum class Mode {
        ON_DEVICE,
        API
    }
}
