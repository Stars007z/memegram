package com.example.memegram

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ManualBackTransition {
    var skipNextPopAnimation by mutableStateOf(false)
}
