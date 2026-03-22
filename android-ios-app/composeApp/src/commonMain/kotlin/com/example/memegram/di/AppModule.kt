package com.example.memegram.di

import com.example.memegram.*
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.local.createPlatformKeyManager
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.network.createHttpClient
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.data.repository.UserRepositoryImpl
import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { Settings() }
    single { createHttpClient() }
    single { SessionManager(get()) }
    single<KeyManager> { createPlatformKeyManager(get()) }
    single { ApiService(get(), get()) }
    single { ThemePreferences(get()) }
    single<UserRepository> { UserRepositoryImpl(get()) }

    viewModelOf(::AuthViewModel)
    viewModelOf(::ChatsViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::ThemeViewModel)
    viewModelOf(::AppearanceViewModel)
    viewModelOf(::LanguageViewModel)
    viewModelOf(::PrivacyViewModel)
    viewModelOf(::NotificationsViewModel)
}