package com.example.gibddochevidets.network

data class BanStatusResponse(
    val is_banned: Boolean,
    val reason: String? = null,
    val ends_at: String? = null
)