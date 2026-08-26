package com.example.gibddochevidets

import com.example.gibddochevidets.network.ApiRepository
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
        saveToken(token)
        sendTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d("FCM", "Received message: ${message.data}")

        // Проверяем, есть ли кастомные данные
        val data = message.data
        val type = data["type"] // "ban_ended", "new_message", etc.

        when (type) {
            "ban_ended" -> {
                // Показываем уведомление о снятии бана
                showNotification(
                    title = "Бан снят",
                    body = "Ваш бан завершён, вы снова можете отправлять сообщения."
                )
                // Отправляем широковещательное сообщение для обновления UI
                sendBanEndedBroadcast()
            }
            "new_message" -> {
                // Если есть уведомление от сервера о новом сообщении
                message.notification?.let {
                    showNotification(it.title, it.body)
                }
                // Можно также отправить broadcast для обновления чата
            }
            else -> {
                // Обычное уведомление
                message.notification?.let {
                    showNotification(it.title, it.body)
                }
            }
        }
    }

    private fun saveToken(token: String) {
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    private fun sendTokenToServer(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = ApiRepository(applicationContext)
                // Повторно регистрируем устройство, чтобы обновить push_token
                repo.registerDevice()
            } catch (e: Exception) {
                Log.e("FCM", "Failed to update token", e)
            }
        }
    }

    private fun sendBanEndedBroadcast() {
        val intent = Intent("BAN_ENDED")
        sendBroadcast(intent)
    }

    private fun showNotification(title: String?, body: String?) {
        val channelId = "gibdd_channel"
        val notificationId = System.currentTimeMillis().toInt()

        // Создаём канал для Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ГИБДД-Очевидец",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления от ГИБДД"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // Intent для открытия приложения при клике на уведомление
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.logo_gibdd)
            .setContentTitle(title ?: "ГИБДД-Очевидец")
            .setContentText(body ?: "Новое сообщение")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // Для Android 13+ запрашиваем разрешение (если ещё не дано)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this).notify(notificationId, builder.build())
            }
        } else {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        }
    }
}