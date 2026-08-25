package com.example.gibddochevidets.network

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val token: String,
    private val clientApp: String = "eyewitness",
    private val onEvent: (String, Map<String, Any?>) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    fun connect() {
        val url = "wss://xn--e1afhclgq.xn--p1ai:4401/api/v1/realtime"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Client-App", clientApp)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WS", "Соединение установлено")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WS", "Получено: $text")
                try {
                    val json = gson.fromJson(text, Map::class.java) as Map<String, Any?>
                    val event = json["event"] as? String ?: return
                    onEvent(event, json)
                } catch (e: Exception) {
                    Log.e("WS", "Ошибка парсинга", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d("WS", "Закрытие: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WS", "Ошибка WebSocket", t)
                // Переподключение через 5 секунд
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connect()
                }, 5000)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Закрытие по запросу")
        webSocket = null
    }
}