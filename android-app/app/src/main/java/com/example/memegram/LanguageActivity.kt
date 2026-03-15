package com.example.memegram

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import java.util.Locale

class LanguageActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)
        ThemeHelper.applyStatusBarColor(this)
        applyWindowInsets(R.id.mainLayout)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAiTranslation)
            .setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) Toast.makeText(this, "AI Translation enabled (Coming soon)", Toast.LENGTH_SHORT).show()
            }

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("My_Lang", "en") ?: "en"

        val btnRussian = findViewById<View>(R.id.btnLangRussian)
        val btnEnglish = findViewById<View>(R.id.btnLangEnglish)
        val checkRu = findViewById<View>(R.id.ivCheckRussian)
        val checkEn = findViewById<View>(R.id.ivCheckEnglish)

        if (currentLang == "ru") checkRu.visibility = View.VISIBLE else checkEn.visibility = View.VISIBLE

        btnRussian.setOnClickListener { setNewLanguage("ru") }
        btnEnglish.setOnClickListener { setNewLanguage("en") }

        val etSearch = findViewById<android.widget.EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase(Locale.getDefault()).trim()
                val showRussian = query.isEmpty() || "russian".contains(query) || "русский".contains(query)
                val showEnglish = query.isEmpty() || "english".contains(query)

                btnRussian.visibility = if (showRussian) View.VISIBLE else View.GONE
                btnEnglish.visibility = if (showEnglish) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setNewLanguage(langCode: String) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("My_Lang", "en")
        if (currentLang == langCode) return

        prefs.edit { putString("My_Lang", langCode) }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
