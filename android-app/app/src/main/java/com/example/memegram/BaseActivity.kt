package com.example.memegram

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val languageCode = prefs.getString("My_Lang", "en") ?: "en"
        super.attachBaseContext(LocaleHelper.setLocale(newBase, languageCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Включаем Edge-to-Edge режим
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onResume() {
        super.onResume()
        applyThemeToCurrentActivity()
    }

    protected open fun applyThemeToCurrentActivity() {
        ThemeHelper.applyStatusBarColor(this)

        val topBar = findViewById<View>(R.id.topBar)
        if (topBar != null) {
            ThemeHelper.applyBackground(
                context = this,
                view = topBar,
                colorKey = ThemeHelper.KEY_TOPBAR_COLOR,
                imageKey = ThemeHelper.KEY_TOPBAR_IMAGE,
                defaultColorHex = ThemeHelper.DEFAULT_TOPBAR
            )
        }
    }

    protected fun applyWindowInsets(viewId: Int) {
        val view = findViewById<View>(viewId) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            // Объединяем системные панели и клавиатуру
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val bars = insets.getInsets(typeMask)

            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            // Возвращаем insets для дальнейшей обработки, если необходимо
            insets
        }
    }
}
