package com.example.gibddochevidets

import com.example.gibddochevidets.network.BanStatusResponse
import com.example.gibddochevidets.network.LiveLocationPointRequest
import com.example.gibddochevidets.network.LiveLocationPointResponse
import com.example.gibddochevidets.network.LiveLocationPointsResponse
import com.example.gibddochevidets.network.LiveLocationStartRequest
import com.example.gibddochevidets.network.MediaRequest
import com.example.gibddochevidets.network.MessageResponse
import com.example.gibddochevidets.network.MessagesResponse
import com.example.gibddochevidets.network.RegisterDeviceRequest
import com.example.gibddochevidets.network.RegisterResponse
import com.example.gibddochevidets.network.SendMessageRequest
import com.example.gibddochevidets.network.StaticLocationRequest
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("api/v1/devices/register")
    suspend fun registerDevice(
        @Header("X-Client-App") clientApp: String,
        @Body request: RegisterDeviceRequest
    ): RegisterResponse

    @POST("api/v1/messages")
    suspend fun sendMessage(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Body request: SendMessageRequest
    ): MessageResponse

    @GET("/health")
    suspend fun health(): Response<Unit>   // нам важен только код ответа

    @GET("/api/v1/devices/me/bans/active")
    suspend fun getActiveBan(
        @Header("Authorization") authorization: String,
        @Header("Client-App") clientApp: String
    ): Response<BanStatusResponse>

    @GET("api/v1/messages/{message_id}/media")
    suspend fun downloadMedia(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("message_id") messageId: String
    ): ResponseBody

    @Multipart
    @POST("api/v1/messages/media/upload")
    suspend fun uploadMedia(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Part file: MultipartBody.Part
    ): MessageResponse

    @GET("api/v1/chats/{observer_device_id}/messages")
    suspend fun getMessages(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("observer_device_id") observerDeviceId: String,
        @Query("after_message_id") afterMessageId: String?,
        @Query("limit") limit: Int
    ): MessagesResponse

    @PATCH("api/v1/messages/{message_id}/delivered")
    suspend fun markDelivered(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("message_id") messageId: String
    ): MessageResponse

    @POST("api/v1/messages/static-location")
    suspend fun sendStaticLocation(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Body request: StaticLocationRequest
    ): MessageResponse

    @POST("api/v1/messages/media")
    suspend fun sendMedia(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Body request: MediaRequest
    ): MessageResponse

    @POST("api/v1/messages/live-location/start")
    suspend fun startLiveLocation(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Body request: LiveLocationStartRequest
    ): MessageResponse

    @POST("api/v1/messages/{message_id}/live-location/points")
    suspend fun sendLiveLocationPoint(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("message_id") messageId: String,
        @Body request: LiveLocationPointRequest
    ): LiveLocationPointResponse

    @POST("api/v1/messages/{message_id}/live-location/stop")
    suspend fun stopLiveLocation(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("message_id") messageId: String
    ): MessageResponse

    @GET("api/v1/messages/{message_id}/live-location/points")
    suspend fun getLiveLocationPoints(
        @Header("Authorization") authorization: String,
        @Header("X-Client-App") clientApp: String,
        @Path("message_id") messageId: String,
        @Query("after_recorded_at") afterRecordedAt: String?,
        @Query("limit") limit: Int
    ): LiveLocationPointsResponse
}