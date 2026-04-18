package com.example.memegram.lifecycle

private class IosAppLifecycleObserver : AppLifecycleObserver {
    // TODO: подписаться на UIApplicationDidBecomeActiveNotification через NSNotificationCenter.
    override fun start(onForeground: () -> Unit) {}
    override fun stop() {}
}

actual fun createAppLifecycleObserver(): AppLifecycleObserver = IosAppLifecycleObserver()
