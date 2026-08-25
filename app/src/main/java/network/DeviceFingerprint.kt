package com.example.gibddochevidets.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * Генерация отпечатка устройства.
 * Если ANDROID_ID недоступен, генерируется случайный UUID, сохраняется в защищённом хранилище.
 */
object DeviceFingerprint {

    private const val PREF_NAME = "device_prefs"
    private const val KEY_FALLBACK_UUID = "fallback_uuid"

    fun get(context: Context): String {
        val androidId = getAndroidId(context)
        return generateFingerprint(context, androidId)
    }

    private fun getAndroidId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            // ANDROID_ID доступен и не является дефолтным (на некоторых устройствах)
            return androidId
        }
        // ANDROID_ID недоступен или дефолтный – используем fallback UUID
        return getOrCreateFallbackUuid(context)
    }

    private fun getOrCreateFallbackUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var uuid = prefs.getString(KEY_FALLBACK_UUID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_FALLBACK_UUID, uuid).apply()
        }
        return uuid
    }

    private fun generateFingerprint(context: Context, androidId: String): String {
        val board = Build.BOARD ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        val hardware = Build.HARDWARE ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""

        val combined = "$androidId|$board|$brand|$device|$hardware|$manufacturer|$model|$product"
        return sha256(combined)
    }

    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("DeviceFingerprint", "SHA-256 error", e)
            // fallback – использовать UUID
            UUID.randomUUID().toString().replace("-", "")
        }
    }
}