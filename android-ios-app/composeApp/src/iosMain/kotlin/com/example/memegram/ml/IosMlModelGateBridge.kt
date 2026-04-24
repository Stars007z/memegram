package com.example.memegram.ml

object IosMlModelGateBridge {
    fun onMemoryPressure() = MlModelGate.onMemoryPressure()
    fun onAppBackgrounded() = MlModelGate.onAppBackgrounded()
}
