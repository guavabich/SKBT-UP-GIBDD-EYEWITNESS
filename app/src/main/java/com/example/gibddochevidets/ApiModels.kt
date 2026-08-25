package com.example.gibddochevidets.network

// ============================================================
// DEVICE REGISTRATION
// ============================================================

data class RegisterDeviceRequest(
    val fingerprint_hash: String,
    val push_token: String?
)

data class RegisterResponse(
    val device_id: String,
    val role: String,
    val access_token: String
)

// ============================================================
// TEXT MESSAGE
// ============================================================

data class SendMessageRequest(
    val message_type: String,
    val observer_device_id: String?,
    val text: String
)

// ============================================================
// STATIC LOCATION
// ============================================================

data class StaticLocationRequest(
    val latitude: Double,
    val longitude: Double
)

data class StaticLocationResponse(
    val latitude: Double,
    val longitude: Double
)

// ============================================================
// MEDIA
// ============================================================

data class MediaRequest(
    val storage_key: String,
    val mime_type: String
)

data class MediaResponse(
    val storage_key: String,
    val mime_type: String,
    val last_viewed_at: String?
)

// ============================================================
// LIVE LOCATION
// ============================================================

data class LiveLocationStartRequest(
    val observer_device_id: String? = null
)

data class LiveLocationPointRequest(
    val latitude: Double,
    val longitude: Double
)

data class LiveLocationResponse(
    val ends_at: String
)


data class LiveLocationPointsResponse(
    val points: List<LiveLocationPointResponse>
)

// ============================================================
// MESSAGE
// ============================================================

data class MessageResponse(
    val message_id: String,
    val observer_device_id: String?,
    val message_type: String?,
    val text: String?,
    val static_location: StaticLocationResponse?,
    val media: MediaResponse?,
    val live_location: LiveLocationResponse?,
    val created_at: String?,
    val delivered_at: String?
)

// ============================================================
// MESSAGES LIST
// ============================================================

data class MessagesResponse(
    val messages: List<MessageResponse>
)