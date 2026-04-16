package com.example.memegram.translation

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TranslationSettings(private val settings: Settings) {

    companion object {
        private const val KEY_AUTO_TRANSLATE = "translation_auto_enabled"
        private const val KEY_TARGET_LANG = "translation_target_lang"
        private const val KEY_BLACKLIST = "translation_blacklist_langs"
    }

    // ── Auto-translate toggle ────────────────────────────────────────────

    private val _autoTranslateEnabled = MutableStateFlow(
        settings.getBoolean(KEY_AUTO_TRANSLATE, false)
    )
    val autoTranslateEnabled: StateFlow<Boolean> = _autoTranslateEnabled.asStateFlow()

    fun setAutoTranslateEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_TRANSLATE, enabled)
        _autoTranslateEnabled.value = enabled
    }

    // ── Target language (default = app language) ─────────────────────────

    private val _targetLanguage = MutableStateFlow(
        settings.getString(KEY_TARGET_LANG, "")
    )
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    fun getEffectiveTargetLang(appLang: String): String {
        val stored = _targetLanguage.value
        return if (stored.isNotBlank()) stored else appLang
    }

    fun setTargetLanguage(lang: String) {
        settings.putString(KEY_TARGET_LANG, lang)
        _targetLanguage.value = lang
        ensureInBlacklist(lang)
    }

    // ── Blacklisted languages (not auto-translated) ─────────────────────

    private val _blacklistedLanguages = MutableStateFlow(
        loadBlacklist()
    )
    val blacklistedLanguages: StateFlow<Set<String>> = _blacklistedLanguages.asStateFlow()

    fun setBlacklistedLanguages(langs: Set<String>) {
        settings.putString(KEY_BLACKLIST, langs.joinToString(","))
        _blacklistedLanguages.value = langs
    }

    fun addToBlacklist(lang: String) {
        val updated = _blacklistedLanguages.value + lang
        setBlacklistedLanguages(updated)
    }

    fun removeFromBlacklist(lang: String) {
        val updated = _blacklistedLanguages.value - lang
        setBlacklistedLanguages(updated)
    }

    private fun ensureInBlacklist(lang: String) {
        if (lang.isNotBlank() && lang !in _blacklistedLanguages.value) {
            addToBlacklist(lang)
        }
    }


    fun syncBlacklistDefaults(appLang: String) {
        ensureInBlacklist(appLang)
        val target = _targetLanguage.value
        if (target.isNotBlank()) {
            ensureInBlacklist(target)
        }
    }

    fun shouldAutoTranslate(sourceLang: String, appLang: String): Boolean {
        if (!_autoTranslateEnabled.value) return false
        if (sourceLang == getEffectiveTargetLang(appLang)) return false
        if (sourceLang in _blacklistedLanguages.value) return false
        return true
    }

    private fun loadBlacklist(): Set<String> {
        val raw = settings.getString(KEY_BLACKLIST, "")
        return if (raw.isBlank()) emptySet()
        else raw.split(",").filter { it.isNotBlank() }.toSet()
    }
}
