package com.example.memegram

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun setLocale(context: Context, languageCode: String): ContextWrapper {
        val locale = Locale.Builder().setLanguage(languageCode).build()

        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)

        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        configuration.setLocales(localeList)

        return ContextWrapper(context.createConfigurationContext(configuration))
    }
}
