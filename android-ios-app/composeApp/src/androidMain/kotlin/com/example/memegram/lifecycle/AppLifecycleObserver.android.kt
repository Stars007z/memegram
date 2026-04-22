package com.example.memegram.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

private class AndroidAppLifecycleObserver : AppLifecycleObserver {
    private var observer: DefaultLifecycleObserver? = null

    override fun start(onForeground: () -> Unit) {
        stop()
        val obs = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                onForeground()
            }
        }
        observer = obs
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
    }

    override fun stop() {
        observer?.let {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        observer = null
    }
}

actual fun createAppLifecycleObserver(): AppLifecycleObserver = AndroidAppLifecycleObserver()
