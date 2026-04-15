package com.example.memegram.di

import com.example.memegram.translation.TranslationManager
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    // get() resolves Android Context (registered via androidContext() in Application)
    single { TranslationManager(get()) }
}
