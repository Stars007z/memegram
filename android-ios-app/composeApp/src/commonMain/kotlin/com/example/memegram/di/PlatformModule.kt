package com.example.memegram.di

import org.koin.core.module.Module

/**
 * Platform-specific Koin module.
 * Android actual: provides TranslationManager(androidContext()) singleton.
 * iOS actual: provides TranslationManager() stub singleton.
 */
expect fun platformModule(): Module
