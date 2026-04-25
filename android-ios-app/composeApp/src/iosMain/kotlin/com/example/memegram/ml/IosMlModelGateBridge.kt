package com.example.memegram.ml

object IosMlModelGateBridge {
    fun onMemoryPressure() = MlModelGate.onMemoryPressure(cancelQueuedAuto = false)
    fun onAppBackgrounded() = MlModelGate.onAppBackgrounded()
}
