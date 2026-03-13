package com.example.memegram

import android.content.Context
import androidx.core.content.edit

object SessionManager {
    private const val PREFS = "session"

    fun save(context: Context, r: AuthResponse) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("access_token",  r.access_token)
            putString("refresh_token", r.refresh_token)
            putString("user_id",       r.user_id)
            putString("device_id",     r.device_id)
            putBoolean("is_primary",   r.is_primary)
            putLong("expires_at",      r.expires_at)
        }

    fun getAccessToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("access_token", null)

    fun isTokenExpired(context: Context): Boolean {
        val exp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("expires_at", 0L)
        if (exp == 0L) return true
        return System.currentTimeMillis() / 1000 >= exp - 60
    }

    fun isLoggedIn(context: Context) = getAccessToken(context) != null

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }

    fun getDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("device_id", null)

}