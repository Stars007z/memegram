package com.example.memegram.di

import com.example.memegram.AppearanceViewModel
import com.example.memegram.AuthViewModel
import com.example.memegram.ChatViewModel
import com.example.memegram.ChatsViewModel
import com.example.memegram.LanguageViewModel
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.ThemePreferences
import com.example.memegram.data.local.createPlatformKeyManager
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.network.createHttpClient
import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import com.example.memegram.ProfileViewModel
import com.example.memegram.ThemeViewModel

val appModule = module {
    single { Settings() }
    single { createHttpClient() }

    single { SessionManager(get()) }
    single<KeyManager> { createPlatformKeyManager(get()) }
    single { ApiService(get()) }
    single { ThemePreferences(get()) }

    viewModelOf(::AuthViewModel)
    viewModelOf(::ChatsViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::AppearanceViewModel)
    single { ProfileViewModel(get()) }
    viewModelOf(::ThemeViewModel)
    viewModelOf(::LanguageViewModel)

}