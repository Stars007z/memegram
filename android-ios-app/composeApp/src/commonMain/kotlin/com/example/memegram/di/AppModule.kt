package com.example.memegram.di

import app.cash.sqldelight.db.SqlDriver
import com.example.memegram.*
import com.example.memegram.data.local.KeyManager
import com.example.memegram.data.local.SessionManager
import com.example.memegram.data.local.createPlatformKeyManager
import com.example.memegram.data.local.createSecureSettings
import com.example.memegram.data.network.ApiService
import com.example.memegram.data.network.createHttpClient
import com.example.memegram.data.repository.ChatRepository
import com.example.memegram.data.repository.ChatRepositoryImpl
import com.example.memegram.data.repository.ContactsRepository
import com.example.memegram.data.repository.ContactsRepositoryImpl
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.data.repository.UserRepositoryImpl
import com.example.memegram.database.AppDatabase
import com.example.memegram.audio.GlobalAudioPlayer
import com.example.memegram.mls.MlsManager
import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single<Settings> { Settings() }
    single<Settings>(named("secure")) { createSecureSettings() }
    single { SessionManager(get(named("secure"))) }
    single { createHttpClient() }
    single<KeyManager> { createPlatformKeyManager(get()) }
    single { ApiService(get(), get(), baseUrl = "http://10.0.2.2:8000") }
    single { ThemePreferences(get()) }
    single<UserRepository> { UserRepositoryImpl(get()) }
    single<ContactsRepository> { ContactsRepositoryImpl(get()) }
    single<MlsManager> {
        MlsManager(
            sessionManager = get(),
            settings       = get()
        )
    }
    single<SqlDriver> { createDatabaseDriver() }
    single { AppDatabase(get()) }
    single<ChatRepository> { ChatRepositoryImpl(get(), get()) }
    single { GlobalAudioPlayer() }
    single { AvatarCache(get()) }

    viewModelOf(::AuthViewModel)
    viewModelOf(::ChatsViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::ThemeViewModel)
    viewModelOf(::AppearanceViewModel)
    viewModelOf(::LanguageViewModel)
    viewModelOf(::PrivacyViewModel)
    viewModelOf(::NotificationsViewModel)
    viewModelOf(::BlackListViewModel)
    viewModelOf(::ContactsViewModel)
    viewModelOf(::StorageViewModel)
    viewModelOf(::LinkedDevicesViewModel)
    viewModelOf(::AddDeviceViewModel)
    viewModelOf(::GroupProfileViewModel)
    viewModelOf(::UserProfileViewModel)
}