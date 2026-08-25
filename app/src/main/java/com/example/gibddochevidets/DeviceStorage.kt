package com.example.gibddochevidets.network

import android.content.Context

class DeviceStorage(context: Context) {

    private val preferences = context.getSharedPreferences(
        "eyewitness_api",
        Context.MODE_PRIVATE
    )

    var deviceId: String?
        get() = preferences.getString("device_id", null)
        set(value) {
            preferences.edit()
                .putString("device_id", value)
                .apply()
        }

    var accessToken: String?
        get() = preferences.getString("access_token", null)
        set(value) {
            preferences.edit()
                .putString("access_token", value)
                .apply()
        }

    var role: String?
        get() = preferences.getString("role", null)
        set(value) {
            preferences.edit()
                .putString("role", value)
                .apply()
        }

    fun isRegistered(): Boolean {
        return !deviceId.isNullOrBlank() &&
                !accessToken.isNullOrBlank()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}