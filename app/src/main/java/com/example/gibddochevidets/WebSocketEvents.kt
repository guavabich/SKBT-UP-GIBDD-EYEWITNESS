package com.example.gibddochevidets.network

import com.google.gson.annotations.SerializedName

// Базовый класс для всех событий
data class WsEvent<T>(
    val event: String,
    val data: T? = null,
    // для некоторых событий поля могут быть на верхнем уровне
    val message: MessageResponse? = null,
    val point: LiveLocationPointResponse? = null,
    val ban: BanStatusResponse? = null,
    val device_id: String? = null,
    val client_app: String? = null,
    val role: String? = null,
    val actor_device_id: String? = null,
    val target_device_id: String? = null,
    val action: String? = null,
    val message_id: String? = null,
    val observer_device_id: String? = null,
    val sender_device_id: String? = null
)

// Событие подключения
data class WsConnected(
    val device_id: String,
    val client_app: String,
    val role: String?
)

// Событие нового сообщения
data class WsMessageCreated(
    val message: MessageResponse
)

// Событие новой точки live-локации
data class WsLiveLocationPoint(
    val message_id: String,
    val observer_device_id: String,
    val sender_device_id: String,
    val point: LiveLocationPointResponse
)

// Событие остановки live-локации
data class WsLiveLocationStopped(
    val message: MessageResponse
)

// Событие бана
data class WsObserverBanned(
    val ban: BanStatusResponse
)

// Событие смены роли
data class WsRoleChanged(
    val actor_device_id: String,
    val target_device_id: String,
    val action: String, // "ASSIGNED", "REPLACED", "REMOVED"
    val role: String?
)

// Событие доставки сообщения
data class WsMessageDelivered(
    val message: MessageResponse
)