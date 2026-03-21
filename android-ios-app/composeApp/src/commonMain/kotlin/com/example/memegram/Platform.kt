package com.example.memegram

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform