package com.example.gibddochevidets.data

import android.content.Context

class SessionManager(
    context: Context
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(
            "eyewitness_session",
            Context.MODE_PRIVATE
        )

    var deviceId: String?
        get() =
            preferences.getString(
                KEY_DEVICE_ID,
                null
            )
        set(value) {
            preferences.edit()
                .putString(
                    KEY_DEVICE_ID,
                    value
                )
                .apply()
        }

    var accessToken: String?
        get() =
            preferences.getString(
                KEY_ACCESS_TOKEN,
                null
            )
        set(value) {
            preferences.edit()
                .putString(
                    KEY_ACCESS_TOKEN,
                    value
                )
                .apply()
        }

    var fingerprintHash: String?
        get() =
            preferences.getString(
                KEY_FINGERPRINT,
                null
            )
        set(value) {
            preferences.edit()
                .putString(
                    KEY_FINGERPRINT,
                    value
                )
                .apply()
        }

    fun isRegistered(): Boolean {

        return !deviceId.isNullOrBlank() &&
                !accessToken.isNullOrBlank()
    }

    fun clear() {

        preferences.edit()
            .clear()
            .apply()
    }

    companion object {

        private const val KEY_DEVICE_ID =
            "device_id"

        private const val KEY_ACCESS_TOKEN =
            "access_token"

        private const val KEY_FINGERPRINT =
            "fingerprint_hash"
    }
}