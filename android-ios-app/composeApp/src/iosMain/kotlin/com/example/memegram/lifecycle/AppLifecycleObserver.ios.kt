package com.example.memegram.lifecycle

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification

private class IosAppLifecycleObserver : AppLifecycleObserver {
    private var observer: Any? = null

    override fun start(onForeground: () -> Unit) {
        stop()
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            onForeground()
        }
    }

    override fun stop() {
        observer?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
        }
        observer = null
    }
}

actual fun createAppLifecycleObserver(): AppLifecycleObserver = IosAppLifecycleObserver()
