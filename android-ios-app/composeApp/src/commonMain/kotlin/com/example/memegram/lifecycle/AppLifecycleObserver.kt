package com.example.memegram.lifecycle

interface AppLifecycleObserver {
    fun start(onForeground: () -> Unit)

    fun stop()
}

expect fun createAppLifecycleObserver(): AppLifecycleObserver
