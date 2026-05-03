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
import com.example.memegram.data.repository.NotificationsRepository
import com.example.memegram.data.repository.NotificationsRepositoryImpl
import com.example.memegram.data.repository.ProfileRepository
import com.example.memegram.data.repository.UserRepository
import com.example.memegram.data.repository.UserRepositoryImpl
import com.example.memegram.data.wipe.ClientDataWiper
import com.example.memegram.data.wipe.createClientDataWiper
import com.example.memegram.database.AppDatabase
import com.example.memegram.audio.GlobalAudioPlayer
import com.example.memegram.auth.SessionRefresher
import com.example.memegram.lifecycle.AppLifecycleObserver
import com.example.memegram.lifecycle.createAppLifecycleObserver
import com.example.memegram.mls.MlsManager
import com.example.memegram.notifications.NotificationPrefs
import com.example.memegram.push.PushTokenProvider
import com.example.memegram.push.createPushTokenProvider
import com.example.memegram.translation.TranslationService
import com.example.memegram.translation.TranslationSettings
import com.example.memegram.translation.createTranslationService
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
    single { ApiService(get(), get(), baseUrl = "https://memegram.win") }
    single { ThemePreferences(get()) }
    single { AppearanceRepository(get(), get()) }
    single<UserRepository> { UserRepositoryImpl(get(), get(), get()) }
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
    single { ProfileRepository(get(), get(), get()) }
    single { BlockedUsersCache(get(), get()) }
    single<TranslationService> {
        val service = createTranslationService(
            httpClient = get(),
            modelBaseUrl = "https://models.memegram.win/memegram-models",
        )
        com.example.memegram.ml.MlModelGate.setReleaseHook { service.releaseModel() }
        service
    }
    single { TranslationSettings(get()) }
    single { NotificationPrefs(get()) }

    single<PushTokenProvider> { createPushTokenProvider() }
    single<NotificationsRepository> { NotificationsRepositoryImpl(get(), get()) }
    single<AppLifecycleObserver> { createAppLifecycleObserver() }
    single { SessionRefresher(get(), get(), get(), get(), get()) }
    single<ClientDataWiper> {
        createClientDataWiper(
            plainSettings = get(),
            secureSettings = get(named("secure")),
            mlsManager = get(),
            database = get(),
        )
    }

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
