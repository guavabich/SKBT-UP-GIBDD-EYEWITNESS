package com.example.gibddochevidets

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.provider.OpenableColumns
import com.example.gibddochevidets.network.BanStatusResponse
import com.example.gibddochevidets.network.LiveLocationPointResponse
import com.google.gson.Gson
import com.example.gibddochevidets.network.WebSocketManager
import java.io.IOException
import android.content.Context
import android.os.Environment
import android.app.AlertDialog
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.content.ContentValues
import android.util.LruCache
import android.util.Log
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.widget.MediaController
import android.widget.Toast
import com.example.gibddochevidets.network.ApiRepository
import com.example.gibddochevidets.network.MessageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.io.File

class ChatActivity : Activity() {

    // ============================================================
    // VIEWS
    // ============================================================

    private var webSocketManager: WebSocketManager? = null
    private var pendingMediaUri: Uri? = null
    private var pendingLocation: Pair<Double, Double>? = null
    private var pendingText: String? = null
    private lateinit var chatRoot: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var messagesContainer: LinearLayout

    private lateinit var contextButtonsContainer: LinearLayout
    private lateinit var contextPhotoButton: TextView
    private lateinit var contextLocationButton: TextView

    private lateinit var messageInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var backButton: TextView
    private lateinit var attachButton: TextView

    private lateinit var attachmentPanel: LinearLayout
    private lateinit var photoOptions: LinearLayout
    private lateinit var locationOptions: LinearLayout

    private lateinit var photoTab: LinearLayout
    private lateinit var locationTab: LinearLayout

    private lateinit var photoTabTitle: TextView
    private lateinit var locationTabTitle: TextView

    private lateinit var openCamera: TextView
    private lateinit var openGallery: TextView

    private lateinit var currentLocation: TextView
    private lateinit var chooseOnMap: TextView
    private lateinit var shareLocation: TextView

    private lateinit var galleryGrid: LinearLayout

    // ============================================================
    // ATTACHMENT POPUP
    // ============================================================
// ============================================================
// OFFLINE QUEUE
// ============================================================
    private var offlineBanner: LinearLayout? = null
    private val offlinePrefs by lazy { getSharedPreferences("offline_queue", Context.MODE_PRIVATE) }
    private data class OfflineQueueItem(
        val id: String, // timestamp в виде строки
        val type: String, // "TEXT", "MEDIA", "LOCATION"
        val text: String? = null,
        val uri: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )
    private var attachmentPopup: PopupWindow? = null
    private var attachmentPopupRoot: LinearLayout? = null

    private lateinit var popupPhotoTab: LinearLayout
    private lateinit var popupLocationTab: LinearLayout

    // ============================================================
    // NETWORK
    // ============================================================

    private lateinit var repository: ApiRepository

    private val httpClient =
        OkHttpClient.Builder()
            .build()

    // ============================================================
    // COROUTINES
    // ============================================================
    private val prefs by lazy {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val activityJob =
        SupervisorJob()

    private val scope =
        CoroutineScope(
            Dispatchers.Main.immediate +
                    activityJob
        )

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var pollingJob: Job? = null
    private val liveStatusViews = mutableMapOf<String, TextView>()
    private val liveStopButtons = mutableMapOf<String, View>() // чтобы потом удалить кнопку
    // ============================================================
    // STATE
    // ============================================================

    private val statusViews = mutableMapOf<String, TextView>()
    // ============================================================
// SELECTED MEDIA
// ============================================================

    private val selectedMediaUris =
        mutableListOf<Uri>()
    private var cameraPhotoUri:
            Uri? = null

    private var sendSelectedMediaButton:
            TextView? = null

    private var isSending = false

    // ============================================================
// MEDIA CACHE
// ============================================================

    private val mediaBitmapCache =
        object : LruCache<String, Bitmap>(
            30 * 1024 * 1024
        ) {

            override fun sizeOf(
                key: String,
                bitmap: Bitmap
            ): Int {
                return bitmap.byteCount
            }
        }

    private val mediaLoading =
        mutableSetOf<String>()

    private val mediaFileCache =
        mutableMapOf<String, File>()

    private val banEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "BAN_ENDED") {
                // Очищаем чат
                messagesContainer.removeAllViews()
                lastMessagesSignature = null
                // Загружаем сообщения заново (они должны быть пустыми, если бан снят)
                loadMessages()
                // Показываем уведомление пользователю
                Toast.makeText(
                    this@ChatActivity,
                    "Бан снят, вы снова можете отправлять сообщения",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        flushOfflineQueue()
        val filter = IntentFilter("BAN_ENDED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(banEndedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(banEndedReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(banEndedReceiver) } catch (_: Exception) {}
    }

    private val mediaFileLoading =
        mutableSetOf<String>()

    private var pendingCameraAction: String? = null
    private var lastMessagesSignature: String? = null

    private var isAttachmentOpen = false

    private var isLiveLocationActive = false

    private var liveLocationMessageId: String? = null

    private var liveLocationJob: Job? = null

    // ============================================================
    // LOCATION
    // ============================================================

    private lateinit var locationManager: LocationManager

    private var locationListener: LocationListener? = null
    private lateinit var fusedLocationClient:
            com.google.android.gms.location.FusedLocationProviderClient

    // ============================================================
    // CONSTANTS
    // ============================================================

    private companion object {
        private const val MAX_PHOTO_SIZE_BYTES = 10 * 1024 * 1024L      // 10 МБ
        private const val MAX_VIDEO_SIZE_BYTES = 100 * 1024 * 1024L    // 100 МБ
        private const val PREF_OFFLINE_QUEUE = "offline_queue"
        private const val QUEUE_TEXT = "TEXT"
        private const val QUEUE_MEDIA = "MEDIA"
        private const val QUEUE_LOCATION = "LOCATION"
        private const val REQUEST_VIDEO_CAPTURE = 1008
        private const val REQUEST_CAMERA_CAPTURE = 1006
        const val REQUEST_LOCATION_PICKER = 1007
        const val REQUEST_GALLERY = 1001
        const val REQUEST_CAMERA = 1002
        const val REQUEST_LOCATION = 1003
        const val REQUEST_MAP_PICKER = 1004
        const val REQUEST_PHOTOS_PERMISSION = 1005
        const val STATE_CAMERA_URI = "state_camera_uri"

        const val LIVE_LOCATION_DURATION_MS =
            15 * 60 * 1000L

        const val LIVE_LOCATION_INTERVAL_MS =
            1000L
    }
    private val MOSCOW_ZONE = ZoneId.of("Europe/Moscow")
    // ============================================================
    // CREATE
    // ============================================================

    override fun onSaveInstanceState(
        outState: Bundle
    ) {
        super.onSaveInstanceState(outState)

        cameraPhotoUri?.let { uri ->

            outState.putString(
                STATE_CAMERA_URI,
                uri.toString()
            )
        }
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )


        cameraPhotoUri =
            savedInstanceState
                ?.getString(
                    STATE_CAMERA_URI
                )
                ?.let {
                    Uri.parse(it)
                }

        setContentView(
            R.layout.activity_chat
        )

        repository =
            ApiRepository(
                applicationContext
            )

        locationManager =
            getSystemService(
                LOCATION_SERVICE
            ) as LocationManager
        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(this)

        initViews()
        contextButtonsContainer.visibility = View.GONE
        setupSystemInsets()

        setupOfflineBanner()

        setupButtons()

        loadMessages()

        startPolling()
        val token = repository.getAccessToken()
        if (!token.isNullOrBlank()) {
            webSocketManager = WebSocketManager(
                token = token,
                clientApp = "eyewitness",
                onEvent = { event, data -> handleWebSocketEvent(event, data) }
            )
            webSocketManager?.connect()
        }
    }

    // ============================================================
    // INIT
    // ============================================================

    private fun initViews() {

        chatRoot =
            findViewById(
                R.id.chatRoot
            )

        chatScroll =
            findViewById(
                R.id.chatScroll
            )

        messagesContainer =
            findViewById(
                R.id.messagesContainer
            )

        messageInput =
            findViewById(
                R.id.messageInput
            )

        sendButton =
            findViewById(
                R.id.sendButton
            )
        contextButtonsContainer = findViewById(R.id.contextButtonsContainer)
        contextPhotoButton = findViewById(R.id.contextPhotoButton)
        contextLocationButton = findViewById(R.id.contextLocationButton)

        contextButtonsContainer.visibility = View.GONE

        contextPhotoButton.setOnClickListener {
            toggleAttachmentPanel()
            hideContextButtonsForever()
        }

        contextLocationButton.setOnClickListener {
            // Открываем панель геолокации
            toggleAttachmentPanel()
            // Можно сразу переключить на вкладку геолокации, если хотите
            // Но для простоты просто открываем панель
            hideContextButtonsForever()
        }

        backButton =
            findViewById(
                R.id.backButton
            )

        attachButton =
            findViewById(
                R.id.attachButton
            )

        attachmentPanel =
            findViewById(
                R.id.attachmentPanel
            )

        photoOptions =
            findViewById(
                R.id.photoOptions
            )

        locationOptions =
            findViewById(
                R.id.locationOptions
            )

        photoTab =
            findViewById(
                R.id.photoTab
            )

        locationTab =
            findViewById(
                R.id.locationTab
            )

        photoTabTitle =
            findViewById(
                R.id.photoTabTitle
            )

        locationTabTitle =
            findViewById(
                R.id.locationTabTitle
            )

        openCamera =
            findViewById(
                R.id.openCamera
            )

        openGallery =
            findViewById(
                R.id.openGallery
            )

        currentLocation =
            findViewById(
                R.id.currentLocation
            )

        chooseOnMap =
            findViewById(
                R.id.chooseOnMap
            )

        shareLocation =
            findViewById(
                R.id.shareLocation
            )

        galleryGrid =
            findViewById(
                R.id.galleryGrid
            )
    }

    // ============================================================
    // SYSTEM INSETS
    // ============================================================

    private fun setupSystemInsets() {

        chatRoot.setOnApplyWindowInsetsListener {
                view,
                insets ->

            val systemBars =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                )

            val ime =
                insets.getInsets(
                    WindowInsets.Type.ime()
                )

            val bottom =
                maxOf(
                    systemBars.bottom,
                    ime.bottom
                )

            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                bottom
            )

            insets
        }

        chatRoot.requestApplyInsets()
    }

    // ============================================================
    // BUTTONS
    // ============================================================

    private fun setupButtons() {

        backButton.setOnClickListener {
            finish()
        }

        sendButton.setOnClickListener {
            sendCurrentMessage()
        }

        attachButton.setOnClickListener {
            toggleAttachmentPanel()
        }

        photoTab.setOnClickListener {
            showPhotoOptions()
        }

        locationTab.setOnClickListener {
            showLocationOptions()
        }

        openGallery.setOnClickListener {
            openGallery()
        }

        openCamera.setOnClickListener {
            openCamera()
        }

        currentLocation.setOnClickListener {
            sendCurrentLocation()
        }

        chooseOnMap.setOnClickListener {
            openMapPicker()
        }

        shareLocation.setOnClickListener {
            startLiveLocation()
        }

        messageInput.setOnEditorActionListener {
                _,
                _,
                _ ->

            sendCurrentMessage()

            true
        }
    }
// ============================================================
// OFFLINE QUEUE METHODS
// ============================================================

    private fun getOfflineQueue(): MutableList<OfflineQueueItem> {
        val json = offlinePrefs.getString(PREF_OFFLINE_QUEUE, null) ?: return mutableListOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<MutableList<OfflineQueueItem>>() {}.type
            Gson().fromJson<MutableList<OfflineQueueItem>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveOfflineQueue(queue: List<OfflineQueueItem>) {
        offlinePrefs.edit().putString(PREF_OFFLINE_QUEUE, Gson().toJson(queue)).apply()
        updateOfflineBanner()
    }

    private fun enqueueOffline(item: OfflineQueueItem) {
        val queue = getOfflineQueue()
        queue.add(item)
        saveOfflineQueue(queue)
    }

    private fun updateOfflineBanner() {
        val banner = offlineBanner ?: return
        val queue = getOfflineQueue()
        val count = queue.size
        val expiredCount = queue.count {
            System.currentTimeMillis() - it.id.toLong() > 60_000
        }
        if (count == 0) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        val textView = banner.findViewWithTag<TextView>("offline_text")
        textView?.text = if (expiredCount > 0) {
            "Не отправлено: $count (${expiredCount} просрочено)"
        } else {
            "Не отправлено: $count"
        }
    }

    private fun flushOfflineQueue() {
        if (isSending) return
        val queue = getOfflineQueue()
        if (queue.isEmpty()) {
            updateOfflineBanner()
            return
        }
        scope.launch {
            val remaining = queue.toMutableList()
            var changed = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val age = System.currentTimeMillis() - item.id.toLong() // item.id – это timestamp создания

                // Если прошло больше 60 секунд – не пытаемся автоматически, оставляем в очереди
                if (age > 60_000) {
                    // Можно обновить баннер, чтобы показать, что сообщение просрочено
                    // Но оставляем в очереди для ручной отправки
                    continue // переходим к следующему, не удаляя
                }

                try {
                    when (item.type) {
                        QUEUE_TEXT -> repository.sendMessage(item.text.orEmpty())
                        QUEUE_MEDIA -> item.uri?.let { uri ->
                            repository.uploadMedia(Uri.parse(uri))
                        }
                        QUEUE_LOCATION -> repository.sendStaticLocation(
                            item.latitude ?: return@launch,
                            item.longitude ?: return@launch
                        )
                    }
                    iterator.remove()
                    changed = true
                } catch (e: Exception) {
                    // Если ошибка сети – прерываем цикл, остальное попробуем позже
                    if (e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                        break
                    }
                    // Другие ошибки (например, 400) – удаляем сообщение из очереди
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                saveOfflineQueue(remaining)
            } else {
                updateOfflineBanner()
            }
            if (remaining.isEmpty()) {
                loadMessages()
            }
        }
    }
    private fun setupOfflineBanner() {
        offlineBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = roundedBackground(Color.rgb(255, 247, 225), dp(12).toFloat())
            visibility = View.GONE
        }
        val textView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(100, 75, 20))
            tag = "offline_text"
        }
        val retryButton = TextView(this).apply {
            text = "Повторить"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedBackground(Color.rgb(45, 130, 220), dp(16).toFloat())
            setOnClickListener { flushOfflineQueue() }
        }
        offlineBanner!!.addView(textView, LinearLayout.LayoutParams(0, dp(40), 1f))
        offlineBanner!!.addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        val index = if (chatRoot.childCount > 0) 1 else 0
        chatRoot.addView(offlineBanner, index, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            bottomMargin = dp(4)
        })
    }

    private fun hideContextButtonsForever() {
        contextButtonsContainer.visibility = View.GONE
        prefs.edit().putBoolean("context_buttons_shown", true).apply()
    }

    // ============================================================
    // ATTACHMENT PANEL
    // ============================================================

    private fun toggleAttachmentPanel() {

        if (isAttachmentOpen) {
            closeAttachmentPanel()
        } else {
            showAttachmentPanel()
        }
    }
    private fun handleWebSocketEvent(event: String, data: Map<String, Any?>) {
        when (event) {
            "connected" -> {
                val deviceId = data["device_id"] as? String
                val role = data["role"] as? String
                Log.d("WS", "Подключено: device=$deviceId, role=$role")
            }
            "message_created" -> {
                val messageJson = data["message"] as? Map<*, *>
                if (messageJson != null) {
                    val message = Gson().fromJson(
                        Gson().toJson(messageJson),
                        MessageResponse::class.java
                    )
                    // Добавляем сообщение в чат
                    addMessage(message, repository.getDeviceId())
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                    // Отмечаем доставку (если нужно)
                    scope.launch {
                        try {
                            repository.markDelivered(message.message_id)
                        } catch (_: Exception) { }
                    }
                }
            }
            "live_location_point" -> {
                val messageId = data["message_id"] as? String
                val pointJson = data["point"] as? Map<*, *>
                if (messageId != null && pointJson != null) {
                    val point = Gson().fromJson(
                        Gson().toJson(pointJson),
                        LiveLocationPointResponse::class.java
                    )
                    // Обновляем маркер на карте (если есть)
                    Log.d("WS", "Новая точка для $messageId: ${point.latitude}, ${point.longitude}")
                    // Можно обновить UI, если нужно
                }
            }
            "live_location_stopped" -> {
                val messageJson = data["message"] as? Map<*, *>
                if (messageJson != null) {
                    val message = Gson().fromJson(
                        Gson().toJson(messageJson),
                        MessageResponse::class.java
                    )
                    // Обновляем статус сообщения (live_location.ends_at)
                    // Можно перерисовать сообщение
                    Log.d("WS", "Live-локация остановлена: ${message.message_id}")
                    // Например, найти это сообщение в messagesContainer и обновить
                }
            }
            "observer_banned" -> {
                val banJson = data["ban"] as? Map<*, *>
                if (banJson != null) {
                    val ban = Gson().fromJson(
                        Gson().toJson(banJson),
                        BanStatusResponse::class.java
                    )
                    // Показываем бан-экран
                    runOnUiThread {
                        val intent = Intent(this, BanActivity::class.java)
                        intent.putExtra(BanActivity.EXTRA_REASON, ban.reason)
                        intent.putExtra(BanActivity.EXTRA_ENDS_AT, ban.ends_at)
                        intent.putExtra(BanActivity.EXTRA_PERMANENT, ban.ends_at == null)
                        startActivity(intent)
                        finish()
                    }
                }
            }
            "message_delivered" -> {
                val messageJson = data["message"] as? Map<*, *>
                if (messageJson != null) {
                    val message = Gson().fromJson(Gson().toJson(messageJson), MessageResponse::class.java)
                    // Обновить статус в messagesContainer
                    updateMessageStatus(message.message_id, deliveredAt = message.delivered_at)
                }
            }


            "role_changed" -> {
                // Для административной части, можно обновить UI
                Log.d("WS", "Роль изменена: $data")
            }
            else -> Log.d("WS", "Неизвестное событие: $event")
        }
    }

    private fun updateMessageStatus(messageId: String, deliveredAt: String?) {
        val statusView = statusViews[messageId] ?: return
        if (deliveredAt != null) {
            statusView.text = "  ✓✓"
            statusView.setTextColor(Color.rgb(23, 100, 200))
        } else {
            statusView.text = "  ✓"
            statusView.setTextColor(Color.rgb(120, 130, 140))
        }
    }
    private fun showAttachmentPanel() {

        closeAttachmentPanel()

        isAttachmentOpen = true

        messageInput.clearFocus()

        val root =
            createAttachmentPopup()

        attachmentPopupRoot =
            root

        val popupHeight =
            minOf(
                dp(430),
                (
                        resources.displayMetrics.heightPixels *
                                0.58f
                        ).toInt()
            )

        val popup =
            PopupWindow(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                popupHeight,
                true
            )

        popup.setBackgroundDrawable(
            roundedBackground(
                Color.WHITE,
                dp(22).toFloat()
            )
        )

        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.elevation = dp(12).toFloat()

        popup.setOnDismissListener {

            isAttachmentOpen = false

            attachmentPopup = null

            attachmentPopupRoot = null
        }

        attachmentPopup =
            popup

        popup.showAtLocation(
            window.decorView,
            Gravity.BOTTOM,
            0,
            messageComposerBottomOffset()
        )

        showPhotoOptions()
    }

    // ============================================================
    // CLOSE ATTACHMENT
    // ============================================================

    private fun closeAttachmentPanel() {

        isAttachmentOpen = false

        attachmentPopup?.setOnDismissListener(null)

        attachmentPopup?.dismiss()

        attachmentPopup = null

        attachmentPopupRoot = null

        if (::attachmentPanel.isInitialized) {

            attachmentPanel.visibility =
                View.GONE
        }
    }

    // ============================================================
    // POPUP POSITION
    // ============================================================

    private fun messageComposerBottomOffset(): Int {

        val inputHeight =
            if (
                ::messageInput.isInitialized &&
                messageInput.height > 0
            ) {
                messageInput.height
            } else {
                dp(56)
            }

        val bottomPadding =
            if (::chatRoot.isInitialized) {
                chatRoot.paddingBottom
            } else {
                0
            }

        return inputHeight +
                bottomPadding +
                dp(4)
    }

    // ============================================================
    // CREATE POPUP
    // ============================================================

    private fun createAttachmentPopup():
            LinearLayout {

        val root =
            LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.setPadding(
            dp(10),
            dp(8),
            dp(10),
            dp(4)
        )

        root.background =
            roundedBackground(
                Color.WHITE,
                dp(22).toFloat()
            )

        root.clipToOutline = true

        // --------------------------------------------------------
        // HANDLE
        // --------------------------------------------------------

        val handle =
            View(this)

        handle.background =
            roundedBackground(
                Color.rgb(
                    75,
                    75,
                    82
                ),
                dp(3).toFloat()
            )

        root.addView(
            handle,
            LinearLayout.LayoutParams(
                dp(40),
                dp(5)
            ).apply {

                gravity =
                    Gravity.CENTER_HORIZONTAL

                bottomMargin =
                    dp(6)
            }
        )

        // --------------------------------------------------------
        // TITLE
        // --------------------------------------------------------

        val titleRow =
            LinearLayout(this)

        titleRow.orientation =
            LinearLayout.HORIZONTAL

        titleRow.gravity =
            Gravity.CENTER_VERTICAL

        val close =
            TextView(this)

        close.text =
            "×"

        close.textSize =
            30f

        close.gravity =
            Gravity.CENTER

        close.setTextColor(
            Color.rgb(
                55,
                75,
                105
            )
        )

        close.setOnClickListener {
            closeAttachmentPanel()
        }

        titleRow.addView(
            close,
            LinearLayout.LayoutParams(
                dp(46),
                dp(42)
            )
        )

        val title =
            TextView(this)

        title.text =
            "Фото и видео"

        title.textSize =
            19f

        title.typeface =
            Typeface.DEFAULT_BOLD

        title.gravity =
            Gravity.CENTER

        title.setTextColor(
            Color.rgb(
                25,
                45,
                95
            )
        )

        titleRow.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            )
        )

        titleRow.addView(
            View(this),
            LinearLayout.LayoutParams(
                dp(46),
                dp(42)
            )
        )

        root.addView(
            titleRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        // --------------------------------------------------------
        // PHOTO
        // --------------------------------------------------------

        photoOptions =
            LinearLayout(this)

        photoOptions.orientation =
            LinearLayout.VERTICAL

        photoOptions.setPadding(
            0,
            dp(2),
            0,
            0
        )

        root.addView(
            photoOptions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // --------------------------------------------------------
        // LOCATION
        // --------------------------------------------------------

        locationOptions =
            createPopupLocationOptions()

        locationOptions.visibility =
            View.GONE

        root.addView(
            locationOptions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // --------------------------------------------------------
        // TABS
        // --------------------------------------------------------

        val tabs =
            LinearLayout(this)

        tabs.orientation =
            LinearLayout.HORIZONTAL

        tabs.gravity =
            Gravity.CENTER

        val galleryTab =
            createPopupTab(
                "▧",
                "Галерея"
            )

        val locationTabView =
            createPopupTab(
                "●",
                "Геопозиция"
            )

        galleryTab.setOnClickListener {
            showPhotoOptions()
        }

        locationTabView.setOnClickListener {
            showLocationOptions()
        }

        tabs.addView(
            galleryTab,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        tabs.addView(
            locationTabView,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        root.addView(
            tabs,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        popupPhotoTab =
            galleryTab

        popupLocationTab =
            locationTabView

        return root
    }

    // ============================================================
    // POPUP TAB
    // ============================================================

    private fun createPopupTab(
        icon: String,
        text: String
    ): LinearLayout {

        val tab =
            LinearLayout(this)

        tab.orientation =
            LinearLayout.VERTICAL

        tab.gravity =
            Gravity.CENTER

        val iconView =
            TextView(this)

        iconView.text =
            icon

        iconView.textSize =
            17f

        iconView.gravity =
            Gravity.CENTER

        iconView.setTextColor(
            Color.rgb(
                80,
                90,
                105
            )
        )

        val textView =
            TextView(this)

        textView.text =
            text

        textView.textSize =
            12f

        textView.gravity =
            Gravity.CENTER

        textView.setTextColor(
            Color.rgb(
                95,
                105,
                120
            )
        )

        tab.addView(
            iconView,
            LinearLayout.LayoutParams(
                -2,
                dp(21)
            )
        )

        tab.addView(
            textView,
            LinearLayout.LayoutParams(
                -2,
                dp(22)
            )
        )

        return tab
    }

    // ============================================================
    // LOCATION PANEL
    // ============================================================

    private fun createPopupLocationOptions():
            LinearLayout {

        val root =
            LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.gravity =
            Gravity.CENTER

        root.setPadding(
            dp(2),
            dp(4),
            dp(2),
            dp(4)
        )

        fun createLocationButton(
            text: String,
            dark: Boolean,
            action: () -> Unit
        ): TextView {

            val view =
                TextView(this)

            view.text =
                text

            view.textSize =
                16f

            view.typeface =
                Typeface.DEFAULT_BOLD

            view.gravity =
                Gravity.CENTER

            view.setTextColor(
                if (dark) {
                    Color.WHITE
                } else {
                    Color.rgb(
                        25,
                        45,
                        95
                    )
                }
            )

            view.setPadding(
                dp(14),
                dp(8),
                dp(14),
                dp(8)
            )

            view.background =
                roundedBackground(
                    if (dark) {
                        Color.rgb(
                            25,
                            45,
                            75
                        )
                    } else {
                        Color.rgb(
                            242,
                            245,
                            250
                        )
                    },
                    dp(15).toFloat()
                )

            view.setOnClickListener {
                action()
            }

            return view
        }

        root.addView(
            createLocationButton(
                "📍  Моя геопозиция",
                false
            ) {
                sendCurrentLocation()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )

        root.addView(
            createLocationButton(
                "🗺  Выбрать на карте",
                false
            ) {
                openMapPicker()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )

        root.addView(
            createLocationButton(
                "📡  Передавать геопозицию",
                true
            ) {
                startLiveLocation()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(52)
            )
        )

        return root
    }

    // ============================================================
    // BACKGROUND
    // ============================================================

    private fun roundedBackground(
        color: Int,
        radius: Float
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(color)

            cornerRadius =
                radius
        }
    }

    // ============================================================
    // PHOTO TAB
    // ============================================================

    private fun showPhotoOptions() {

        if (attachmentPopupRoot == null) {
            return
        }

        photoOptions.visibility =
            View.VISIBLE

        locationOptions.visibility =
            View.GONE

        buildPopupPhotoPanel()

        if (hasPhotoPermission()) {

            loadGalleryPreview()

        } else {

            requestPhotoPermission()
        }
    }

    // ============================================================
    // PHOTO PANEL
    // ============================================================

    private fun buildPopupPhotoPanel() {

        photoOptions.removeAllViews()

        val container =
            LinearLayout(this)

        container.orientation =
            LinearLayout.VERTICAL

        container.setPadding(
            0,
            0,
            0,
            dp(4)
        )

        // ========================================================
        // GALLERY SCROLL
        // ========================================================

        val scroll =
            ScrollView(this)

        scroll.isFillViewport =
            true

        scroll.overScrollMode =
            View.OVER_SCROLL_NEVER

        galleryGrid =
            LinearLayout(this)

        galleryGrid.orientation =
            LinearLayout.VERTICAL

        galleryGrid.setPadding(
            0,
            dp(2),
            0,
            dp(2)
        )

        scroll.addView(
            galleryGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // ========================================================
        // SEND BUTTON
        // ========================================================

        sendSelectedMediaButton =
            TextView(this)

        sendSelectedMediaButton!!.text =
            "Выберите медиафайл"

        sendSelectedMediaButton!!.textSize =
            15f

        sendSelectedMediaButton!!.typeface =
            Typeface.DEFAULT_BOLD

        sendSelectedMediaButton!!.gravity =
            Gravity.CENTER

        sendSelectedMediaButton!!.setTextColor(
            Color.WHITE
        )

        sendSelectedMediaButton!!.background =
            roundedBackground(
                Color.rgb(
                    45,
                    130,
                    220
                ),
                dp(16).toFloat()
            )

        sendSelectedMediaButton!!.isEnabled =
            false

        sendSelectedMediaButton!!.alpha =
            0.5f

        sendSelectedMediaButton!!.setOnClickListener {

            sendSelectedMedia()
        }

        val buttonParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )

        buttonParams.setMargins(
            dp(8),
            dp(6),
            dp(8),
            dp(4)
        )

        container.addView(
            sendSelectedMediaButton,
            buttonParams
        )

        photoOptions.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        updateSelectedMediaButton()
    }

    //повторная попытка подключения
    //private fun showRetryDialog(
        //errorMessage: String,
        //retryAction: () -> Unit
    //) {
        //AlertDialog.Builder(this)
            //.setTitle("Ошибка соединения")
            //.setMessage("$errorMessage\n\nПроверьте интернет-соединение и попробуйте снова.")
            //.setPositiveButton("Повторить попытку") { _, _ ->
                //retryAction()
            //}
            //.setNegativeButton("Отмена", null)
            //.setCancelable(true)
            //.show()
    //}
    // ============================================================
    // LOCATION TAB
    // ============================================================

    private fun showLocationOptions() {

        if (attachmentPopupRoot == null) {
            return
        }

        photoOptions.visibility =
            View.GONE

        locationOptions.visibility =
            View.VISIBLE
    }

    // ============================================================
    // GALLERY
    // ============================================================

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("image/*", "video/*")
                )
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_GALLERY)
        } catch (e: Exception) {
            Log.e("MEDIA_DEBUG", "Не удалось открыть галерею", e)
            Toast.makeText(this, "Галерея недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
// CAMERA-VIDEO
// ============================================================

    private fun openVideoCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Запоминаем, что мы хотим видео
            pendingCameraAction = "video"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }

        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "GIBDD_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GIBDD")
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: run {
                    Toast.makeText(this, "Не удалось создать файл видео", Toast.LENGTH_LONG).show()
                    return
                }

            cameraPhotoUri = uri  // переиспользуем поле, но лучше завести videoUri, но можно и так

            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_VIDEO_CAPTURE)
        } catch (e: Exception) {
            cameraPhotoUri = null
            Toast.makeText(this, "Ошибка видео: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("CAMERA_DEBUG", "openVideoCamera error", e)
        }
    }

    // ============================================================
// CAMERA
// ============================================================

    private fun openCamera() {

        if (
            checkSelfPermission(
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCameraAction = "photo"
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                REQUEST_CAMERA
            )

            return
        }

        try {

            val values =
                ContentValues().apply {

                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        "GIBDD_${System.currentTimeMillis()}.jpg"
                    )

                    put(
                        MediaStore.Images.Media.MIME_TYPE,
                        "image/jpeg"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES +
                                    "/GIBDD"
                        )
                    }
                }

            val uri =
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )

            if (uri == null) {

                Toast.makeText(
                    this,
                    "Не удалось создать файл фотографии",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            cameraPhotoUri = uri

            val intent =
                Intent(
                    MediaStore.ACTION_IMAGE_CAPTURE
                )

            intent.putExtra(
                MediaStore.EXTRA_OUTPUT,
                uri
            )

            intent.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            startActivityForResult(
                intent,
                REQUEST_CAMERA_CAPTURE
            )

        } catch (e: Exception) {

            cameraPhotoUri = null

            Toast.makeText(
                this,
                "Ошибка камеры: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            Log.e(
                "CAMERA_DEBUG",
                "openCamera error",
                e
            )
        }
    }
    //video selected
    private fun onVideoSelected(uri: Uri) {
        if (isSending) return

        // Проверка размера
        val size = getFileSize(uri)
        if (size > MAX_VIDEO_SIZE_BYTES) {
            Toast.makeText(this, "Видео превышает 100 МБ", Toast.LENGTH_LONG).show()
            return
        }
        // Если size == -1, пропускаем проверку

        pendingMediaUri = uri
        isSending = true
        sendButton.isEnabled = false
        attachButton.isEnabled = false
        messageInput.isEnabled = false

        Toast.makeText(this, "Отправляю видео...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.uploadMedia(uri)
                }
                if (isFinishing || isDestroyed) return@launch

                pendingMediaUri = null

                val deviceId = repository.getDeviceId()
                addMessage(result, deviceId)
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                Toast.makeText(this@ChatActivity, "Видео отправлено", Toast.LENGTH_SHORT).show()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                val isNetworkError = e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException
                if (isNetworkError) {
                    enqueueOffline(OfflineQueueItem(
                        id = System.currentTimeMillis().toString(),
                        type = QUEUE_MEDIA,
                        uri = uri.toString()
                    ))
                    Toast.makeText(this@ChatActivity, "Нет интернета. Видео сохранено в очередь.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ChatActivity, "Не удалось отправить видео: ${e.message ?: "неизвестная ошибка"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSending = false
                if (!isFinishing && !isDestroyed) {
                    sendButton.isEnabled = true
                    attachButton.isEnabled = true
                    messageInput.isEnabled = true
                }
            }
        }
    }
    private fun getFileSize(uri: Uri): Long {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return -1L
    }

    private fun onPhotoSelected(uri: Uri) {
        if (isSending) return

        // Проверка размера
        val size = getFileSize(uri)
        if (size > MAX_PHOTO_SIZE_BYTES) {
            Toast.makeText(this, "Фото превышает 10 МБ", Toast.LENGTH_LONG).show()
            return
        }
        // Если size == -1, пропускаем проверку (не удалось определить)

        pendingMediaUri = uri
        isSending = true
        sendButton.isEnabled = false
        attachButton.isEnabled = false
        messageInput.isEnabled = false

        Toast.makeText(this, "Отправляю фото...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.uploadMedia(uri)
                }
                if (isFinishing || isDestroyed) return@launch

                pendingMediaUri = null

                val deviceId = repository.getDeviceId()
                addMessage(result, deviceId)
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                Toast.makeText(this@ChatActivity, "Фото отправлено", Toast.LENGTH_SHORT).show()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                val isNetworkError = e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException
                if (isNetworkError) {
                    enqueueOffline(OfflineQueueItem(
                        id = System.currentTimeMillis().toString(),
                        type = QUEUE_MEDIA,
                        uri = uri.toString()
                    ))
                    Toast.makeText(this@ChatActivity, "Нет интернета. Фото сохранено в очередь.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ChatActivity, "Не удалось отправить фото: ${e.message ?: "неизвестная ошибка"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSending = false
                if (!isFinishing && !isDestroyed) {
                    sendButton.isEnabled = true
                    attachButton.isEnabled = true
                    messageInput.isEnabled = true
                }
            }
        }
    }

    // ============================================================
// ACTIVITY RESULT
// ============================================================

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        // ============================================================
        // КАМЕРА: СНИМАННОЕ ФОТО
        // ============================================================

        if (
            requestCode == REQUEST_CAMERA_CAPTURE &&
            resultCode == Activity.RESULT_OK
        ) {

            val uri = cameraPhotoUri

            if (uri != null) {
                onPhotoSelected(uri)
            }

            cameraPhotoUri = null

            return
        }
        //VIDEO
        if (requestCode == REQUEST_VIDEO_CAPTURE && resultCode == Activity.RESULT_OK) {
            val uri = cameraPhotoUri
            if (uri != null) {
                onVideoSelected(uri)   // будет отправлять видео
            }
            cameraPhotoUri = null
            return
        }

        // ============================================================
        // ГАЛЕРЕЯ: ФОТО + ВИДЕО
        // ============================================================

        if (
            requestCode == REQUEST_GALLERY &&
            resultCode == Activity.RESULT_OK
        ) {

            val uris =
                mutableListOf<Uri>()

            data?.clipData?.let { clip ->

                for (i in 0 until clip.itemCount) {

                    uris += clip
                        .getItemAt(i)
                        .uri
                }
            }

            data?.data?.let { uri ->

                if (!uris.contains(uri)) {
                    uris += uri
                }
            }

            if (uris.isNotEmpty()) {

                selectedMediaUris.clear()

                selectedMediaUris.addAll(
                    uris.take(10)
                )

                updateSelectedMediaButton()

                sendSelectedMedia()
            }

            return
        }

        // ============================================================
// КАРТА — СТАТИЧЕСКАЯ ГЕОЛОКАЦИЯ
// ============================================================

        if (requestCode == REQUEST_MAP_PICKER) {

            Log.d(
                "LOCATION_DEBUG",
                "Map result: resultCode=$resultCode data=$data"
            )

            // --------------------------------------------------------
            // Пользователь отменил выбор
            // --------------------------------------------------------

            if (
                resultCode != Activity.RESULT_OK ||
                data == null
            ) {

                Log.d(
                    "LOCATION_DEBUG",
                    "Выбор точки отменён"
                )

                return
            }

            // --------------------------------------------------------
            // Получаем координаты из MapPickerActivity
            // --------------------------------------------------------

            val latitude =
                data.getDoubleExtra(
                    MapWebViewActivity.EXTRA_LATITUDE,
                    Double.NaN
                )

            val longitude =
                data.getDoubleExtra(
                    MapWebViewActivity.EXTRA_LONGITUDE,
                    Double.NaN
                )

            Log.d(
                "LOCATION_DEBUG",
                "Получены координаты: lat=$latitude lon=$longitude"
            )

            // --------------------------------------------------------
            // Проверяем координаты
            // --------------------------------------------------------

            if (
                latitude.isNaN() ||
                longitude.isNaN()
            ) {

                Toast.makeText(
                    this,
                    "Не удалось получить координаты",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            if (
                latitude !in -90.0..90.0 ||
                longitude !in -180.0..180.0
            ) {

                Toast.makeText(
                    this,
                    "Некорректные координаты",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            // ========================================================
            // ОТПРАВЛЯЕМ СТАТИЧЕСКУЮ ТОЧКУ
            // ========================================================

            scope.launch {

                try {

                    Log.d(
                        "LOCATION_DEBUG",
                        "Отправляю static location..."
                    )

                    // ------------------------------------------------
                    // Отправляем через ТВОЙ ApiRepository
                    // ------------------------------------------------

                    val sentMessage =
                        withContext(
                            Dispatchers.IO
                        ) {

                            repository.sendStaticLocation(
                                latitude = latitude,
                                longitude = longitude
                            )
                        }

                    Log.d(
                        "LOCATION_DEBUG",
                        "Static location успешно отправлена"
                    )

                    Log.d(
                        "LOCATION_DEBUG",
                        "message_id=${sentMessage.message_id}"
                    )

                    if (
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@launch
                    }

                    // =================================================
                    // НЕ СОЗДАЁМ САМОСТОЯТЕЛЬНОЕ СООБЩЕНИЕ.
                    //
                    // Сервер уже создал MessageResponse.
                    // Просто заново получаем сообщения и
                    // renderMessages() сам отобразит STATIC_LOCATION.
                    // =================================================

                    val messages =
                        withContext(
                            Dispatchers.IO
                        ) {

                            repository.getMessages(
                                afterMessageId = null
                            )
                        }

                    if (
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@launch
                    }

                    renderMessages(
                        messages = messages,
                        scrollToBottom = true
                    )

                    Toast.makeText(
                        this@ChatActivity,
                        "Местоположение отправлено",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (
                    e: CancellationException
                ) {

                    throw e

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        "LOCATION_DEBUG",
                        "Ошибка отправки static location",
                        e
                    )

                    if (
                        !isFinishing &&
                        !isDestroyed
                    ) {

                        Toast.makeText(
                            this@ChatActivity,
                            "Ошибка отправки местоположения: ${
                                e.message ?: "неизвестная ошибка"
                            }",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            return
        }
    }

    // ============================================================
    // LOAD RECENT PHOTOS
    // ============================================================

    private fun loadGalleryPreview() {

        if (!::galleryGrid.isInitialized) {
            return
        }

        galleryGrid.removeAllViews()

        if (!hasPhotoPermission()) {
            return
        }

        // ========================================================
        // CAMERA — ALWAYS FIRST
        // ========================================================

        addCameraTile()
        //addVideoCameraTile()

        scope.launch {

            val photos =
                withContext(Dispatchers.IO) {

                    queryRecentPhotoUris(14)
                }

            if (
                isFinishing ||
                isDestroyed
            ) {
                return@launch
            }

            photos.forEach { uri ->

                addGalleryThumbnail(
                    uri
                )
            }

            if (photos.isEmpty()) {

                val empty =
                    TextView(
                        this@ChatActivity
                    )

                empty.text =
                    "Нет фотографий в галерее"

                empty.textSize =
                    14f

                empty.gravity =
                    Gravity.CENTER

                empty.setTextColor(
                    Color.rgb(
                        125,
                        130,
                        140
                    )
                )

                empty.setPadding(
                    0,
                    dp(12),
                    0,
                    dp(12)
                )

                galleryGrid.addView(
                    empty,
                    LinearLayout.LayoutParams(
                        -1,
                        dp(42)
                    )
                )
            }
        }
    }

    // ============================================================
    // QUERY PHOTOS
    // ============================================================

    private fun queryRecentPhotoUris(
        limit: Int
    ): List<Uri> {

        val result =
            mutableListOf<Uri>()

        val projection =
            arrayOf(
                MediaStore.Images.Media._ID
            )

        val collection =
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {

            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Images.Media._ID
                    )

                while (
                    cursor.moveToNext() &&
                    result.size < limit
                ) {

                    val id =
                        cursor.getLong(
                            idColumn
                        )

                    result.add(
                        Uri.withAppendedPath(
                            collection,
                            id.toString()
                        )
                    )
                }
            }

        } catch (
            _: SecurityException
        ) {
        } catch (
            _: Exception
        ) {
        }

        return result
    }
    //video title
    //private fun addVideoCameraTile() {
        //addGalleryTile(
            //size = galleryTileSize(),
            //content = createVideoCameraView(),
            //onClick = { openVideoCamera() }
        //)
    //}

    //private fun createVideoCameraView(): View {
        //val layout = LinearLayout(this).apply {
          //  orientation = LinearLayout.VERTICAL
          //  gravity = Gravity.CENTER
          //  setPadding(dp(4), dp(4), dp(4), dp(4))
          //  background = roundedBackground(Color.rgb(24, 39, 65), dp(12).toFloat())
        //}
        //val icon = TextView(this).apply {
           // text = "🎥"
           // textSize = 28f
         //   gravity = Gravity.CENTER
        //}
        //val text = TextView(this).apply {
          //  text = "Видео"
          //  textSize = 11f
          //  typeface = Typeface.DEFAULT_BOLD
          //  setTextColor(Color.WHITE)
          //  gravity = Gravity.CENTER
        //}
        //layout.addView(icon, LinearLayout.LayoutParams(-2, dp(36)))
        //layout.addView(text, LinearLayout.LayoutParams(-2, dp(20)))
      //  return layout
    //}

    // ============================================================
    // CAMERA TILE
    // ============================================================
    private fun showCameraOptionsDialog() {
        val options = arrayOf("Сделать фото", "Записать видео")
        AlertDialog.Builder(this)
            .setTitle("Камера")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openVideoCamera()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addCameraTile() {

        addGalleryTile(
            size =
                galleryTileSize(),

            content =
                createCameraView(),

            onClick = {
                showCameraOptionsDialog()
            }
        )
    }

    // ============================================================
    // CAMERA VIEW
    // ============================================================

    private fun createCameraView():
            View {

        val layout =
            LinearLayout(this)

        layout.orientation =
            LinearLayout.VERTICAL

        layout.gravity =
            Gravity.CENTER

        layout.setPadding(
            dp(4),
            dp(4),
            dp(4),
            dp(4)
        )

        layout.background =
            roundedBackground(
                Color.rgb(
                    24,
                    39,
                    65
                ),
                dp(12).toFloat()
            )

        val icon =
            TextView(this)

        icon.text =
            "📷"

        icon.textSize =
            28f

        icon.gravity =
            Gravity.CENTER

        val text =
            TextView(this)

        text.text =
            "Камера"

        text.textSize =
            11f

        text.typeface =
            Typeface.DEFAULT_BOLD

        text.setTextColor(
            Color.WHITE
        )

        text.gravity =
            Gravity.CENTER

        layout.addView(
            icon,
            LinearLayout.LayoutParams(
                -2,
                dp(36)
            )
        )

        layout.addView(
            text,
            LinearLayout.LayoutParams(
                -2,
                dp(20)
            )
        )

        return layout
    }

    // ============================================================
    // PHOTO THUMBNAIL
    // ============================================================

    private fun addGalleryThumbnail(uri: Uri) {
        val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        val isVideo = mime.startsWith("video/")

        // Контейнер для миниатюры
        val container = FrameLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(galleryTileSize(), galleryTileSize())
        container.background = roundedBackground(Color.rgb(235, 235, 238), dp(12).toFloat())
        container.clipToOutline = true

        // Изображение
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(image)

        // Если видео — добавляем наложение
        if (isVideo) {
            // Затемнение (чтобы текст читался)
            val overlay = View(this).apply {
                setBackgroundColor(Color.argb(80, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            container.addView(overlay)

            // Иконка play (в центре)
            val playIcon = TextView(this).apply {
                text = "▶"
                textSize = 30f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
            }
            container.addView(playIcon)

            // Длительность (в правом нижнем углу)
            val durationText = TextView(this).apply {
                text = ""
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.argb(180, 0, 0, 0))
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(dp(6), 0, dp(6), dp(6))
                }
            }
            container.addView(durationText)

            // Загружаем длительность асинхронно
            scope.launch {
                val duration = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(this@ChatActivity, uri)
                            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            retriever.release()
                            val seconds = (ms / 1000) % 60
                            val minutes = (ms / 1000) / 60
                            String.format("%02d:%02d", minutes, seconds)
                        } catch (e: Exception) {
                            ""
                        }
                    }.getOrNull() ?: ""
                }
                if (!isFinishing && !isDestroyed) {
                    durationText.text = duration
                }
            }
        }

        // Остальная логика выделения и загрузки миниатюры (как было)
        if (selectedMediaUris.contains(uri)) {
            container.background = GradientDrawable().apply {
                setColor(Color.rgb(220, 235, 255))
                setStroke(dp(3), Color.rgb(45, 130, 220))
                cornerRadius = dp(12).toFloat()
            }
            image.alpha = 0.7f
        } else {
            image.alpha = 1f
        }

        if (isVideo) {
            // Показываем иконку play до загрузки кадра
            image.setImageResource(android.R.drawable.ic_media_play)
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(this@ChatActivity, uri)
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                }
                if (!isFinishing && !isDestroyed && bitmap != null) {
                    image.setImageBitmap(bitmap)
                }
            }
        } else {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                }
                if (!isFinishing && !isDestroyed && bitmap != null) {
                    image.setImageBitmap(bitmap)
                }
            }
        }

        // Добавляем плитку в сетку
        addGalleryTile(
            size = galleryTileSize(),
            content = container,
            onClick = { toggleMediaSelection(uri) }
        )
    }
    // ============================================================
// TOGGLE MEDIA SELECTION
// ============================================================

    private fun toggleMediaSelection(
        uri: Uri
    ) {

        if (
            selectedMediaUris.contains(uri)
        ) {

            selectedMediaUris.remove(uri)

        } else {

            selectedMediaUris.add(uri)
        }

        updateSelectedMediaButton()

        loadGalleryPreview()
    }


    // ============================================================
// UPDATE SEND BUTTON
// ============================================================

    private fun updateSelectedMediaButton() {

        val button =
            sendSelectedMediaButton
                ?: return

        val count =
            selectedMediaUris.size

        if (count == 0) {

            button.text =
                "Выберите медиафайл"

            button.isEnabled =
                false

            button.alpha =
                0.5f

        } else {

            button.text =
                "Отправить ($count)"

            button.isEnabled =
                true

            button.alpha =
                1f
        }
    }

    // ============================================================
// SEND SELECTED MEDIA
// ============================================================

    private fun sendSelectedMedia() {
        if (selectedMediaUris.isEmpty()) return
        if (isSending) return

        // Проверяем размер каждого файла и определяем тип
        val files = selectedMediaUris.toList()
        for (uri in files) {
            val size = getFileSize(uri)
            // Определяем тип по MIME
            val mime = contentResolver.getType(uri)?.lowercase() ?: ""
            val isVideo = mime.startsWith("video/") || mime.startsWith("image/gif")
            val limit = if (isVideo) MAX_VIDEO_SIZE_BYTES else MAX_PHOTO_SIZE_BYTES

            if (size > limit) {
                val type = if (isVideo) "видео" else "фото"
                Toast.makeText(
                    this,
                    "Один из файлов ($type) превышает допустимый размер: ${limit / (1024 * 1024)} МБ",
                    Toast.LENGTH_LONG
                ).show()
                return // прерываем отправку всех
            }
            // Если size == -1, пропускаем (неизвестный размер)
        }

        isSending = true
        sendSelectedMediaButton?.isEnabled = false
        sendButton.isEnabled = false
        attachButton.isEnabled = false
        messageInput.isEnabled = false

        Toast.makeText(this, "Отправляю ${files.size} файл(ов)...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                for (uri in files) {
                    if (isFinishing || isDestroyed) return@launch
                    withContext(Dispatchers.IO) {
                        repository.uploadMedia(uri)
                    }
                }

                if (isFinishing || isDestroyed) return@launch

                selectedMediaUris.clear()
                closeAttachmentPanel()

                val messages = withContext(Dispatchers.IO) {
                    repository.getMessages(null)
                }
                renderMessages(messages, true)

                Toast.makeText(this@ChatActivity, "Файлы отправлены", Toast.LENGTH_SHORT).show()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    if (e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                        // Сохраняем все выбранные файлы в очередь
                        for (uri in files) {
                            enqueueOffline(OfflineQueueItem(
                                id = System.currentTimeMillis().toString(),
                                type = QUEUE_MEDIA,
                                uri = uri.toString()
                            ))
                        }
                        Toast.makeText(this@ChatActivity, "Нет интернета. Файлы сохранены в очередь.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@ChatActivity, "Не удалось отправить файл: ${e.message ?: "неизвестная ошибка"}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isSending = false
                if (!isFinishing && !isDestroyed) {
                    sendButton.isEnabled = true
                    attachButton.isEnabled = true
                    messageInput.isEnabled = true
                    updateSelectedMediaButton()
                }
            }
        }
    }

    // ============================================================
    // PHOTO TILE
    // ============================================================

    private fun addGalleryTile(
        size: Int,
        content: View,
        onClick: () -> Unit
    ) {

        val tile =
            FrameLayout(this)

        tile.background =
            roundedBackground(
                Color.rgb(
                    245,
                    245,
                    247
                ),
                dp(12).toFloat()
            )

        tile.clipToOutline =
            true

        tile.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        tile.setOnClickListener {
            onClick()
        }

        val params =
            LinearLayout.LayoutParams(
                size,
                size
            ).apply {

                setMargins(
                    dp(2),
                    dp(2),
                    dp(2),
                    dp(2)
                )
            }

        var row =
            galleryGrid.getChildAt(
                galleryGrid.childCount - 1
            ) as? LinearLayout

        if (
            row == null ||
            row.childCount >= 3
        ) {

            row =
                LinearLayout(this)

            row.orientation =
                LinearLayout.HORIZONTAL

            row.gravity =
                Gravity.START

            galleryGrid.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        row.addView(
            tile,
            params
        )
    }

    // ============================================================
    // TILE SIZE
    // ============================================================

    private fun galleryTileSize(): Int {

        val width =
            resources
                .displayMetrics
                .widthPixels

        return (
                (
                        width -
                                dp(20) -
                                dp(12)
                        ) / 3
                ).coerceAtLeast(
                dp(70)
            )
    }

    // ============================================================
    // PHOTO PERMISSION
    // ============================================================

    private fun hasPhotoPermission():
            Boolean {

        return when {

            Build.VERSION.SDK_INT >= 34 -> {

                checkSelfPermission(
                    Manifest.permission.READ_MEDIA_IMAGES
                ) ==
                        PackageManager.PERMISSION_GRANTED ||

                        checkSelfPermission(
                            Manifest.permission
                                .READ_MEDIA_VISUAL_USER_SELECTED
                        ) ==
                        PackageManager.PERMISSION_GRANTED
            }

            Build.VERSION.SDK_INT >= 33 -> {

                checkSelfPermission(
                    Manifest.permission.READ_MEDIA_IMAGES
                ) ==
                        PackageManager.PERMISSION_GRANTED
            }

            else -> {

                checkSelfPermission(
                    Manifest.permission
                        .READ_EXTERNAL_STORAGE
                ) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
    }

    // ============================================================
    // REQUEST PHOTO PERMISSION
    // ============================================================

    private fun requestPhotoPermission() {

        if (Build.VERSION.SDK_INT >= 34) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission
                        .READ_MEDIA_VISUAL_USER_SELECTED
                ),
                REQUEST_PHOTOS_PERMISSION
            )

        } else if (
            Build.VERSION.SDK_INT >= 33
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES
                ),
                REQUEST_PHOTOS_PERMISSION
            )

        } else {

            requestPermissions(
                arrayOf(
                    Manifest.permission
                        .READ_EXTERNAL_STORAGE
                ),
                REQUEST_PHOTOS_PERMISSION
            )
        }
    }

    // ============================================================
    // LOCATION PERMISSION
    // ============================================================

    private fun hasLocationPermission():
            Boolean {

        return checkSelfPermission(
            Manifest.permission
                .ACCESS_FINE_LOCATION
        ) ==
                PackageManager.PERMISSION_GRANTED ||

                checkSelfPermission(
                    Manifest.permission
                        .ACCESS_COARSE_LOCATION
                ) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {

        requestPermissions(
            arrayOf(
                Manifest.permission
                    .ACCESS_FINE_LOCATION,

                Manifest.permission
                    .ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION
        )
    }

    // ============================================================
// CURRENT LOCATION
// ============================================================

    // ============================================================
// SEND CURRENT STATIC LOCATION
// ============================================================

    private fun sendCurrentLocation() {

        if (!hasLocationPermission()) {

            requestLocationPermission()

            return
        }

        if (isSending) {
            return
        }

        closeAttachmentPanel()

        Toast.makeText(
            this,
            "Получаю местоположение...",
            Toast.LENGTH_SHORT
        ).show()

        try {

            // ========================================================
            // СНАЧАЛА БЕРЁМ ПОСЛЕДНЮЮ ИЗВЕСТНУЮ КООРДИНАТУ
            // Это намного быстрее GPS-запроса.
            // ========================================================

            fusedLocationClient
                .lastLocation
                .addOnSuccessListener { location ->

                    if (
                        location != null &&
                        location.latitude in -90.0..90.0 &&
                        location.longitude in -180.0..180.0
                    ) {

                        // Есть готовая координата —
                        // отправляем сразу.

                        sendLocation(
                            location.latitude,
                            location.longitude
                        )

                        return@addOnSuccessListener
                    }

                    // ====================================================
                    // ЕСЛИ ПОСЛЕДНЕЙ КООРДИНАТЫ НЕТ —
                    // ЗАПРАШИВАЕМ НОВУЮ
                    // ====================================================

                    requestFreshLocation()

                }
                .addOnFailureListener {

                    requestFreshLocation()
                }

        } catch (
            e: SecurityException
        ) {

            Toast.makeText(
                this,
                "Нет разрешения на геолокацию",
                Toast.LENGTH_LONG
            ).show()

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Не удалось получить геопозицию: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun requestFreshLocation() {

        if (!hasLocationPermission()) {
            return
        }

        try {

            val cancellationTokenSource =
                CancellationTokenSource()

            fusedLocationClient
                .getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellationTokenSource.token
                )
                .addOnSuccessListener { location ->

                    if (location != null) {

                        sendLocation(
                            location.latitude,
                            location.longitude
                        )

                    } else {

                        getLastKnownLocationForSending()
                    }
                }
                .addOnFailureListener {

                    getLastKnownLocationForSending()
                }

        } catch (
            e: SecurityException
        ) {

            Toast.makeText(
                this,
                "Нет разрешения на геолокацию",
                Toast.LENGTH_LONG
            ).show()

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Не удалось получить геопозицию",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    // ============================================================
// SEND STATIC LOCATION MESSAGE
// ============================================================

    private fun sendStaticLocationMessage(
        latitude: Double,
        longitude: Double
    ) {

        if (isSending) {
            return
        }

        if (
            latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {

            Toast.makeText(
                this,
                "Получены некорректные координаты",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        isSending =
            true

        sendButton.isEnabled =
            false

        attachButton.isEnabled =
            false

        scope.launch {

            try {

                // ====================================================
                // ОТПРАВЛЯЕМ СТАТИЧЕСКУЮ ГЕОЛОКАЦИЮ
                // ====================================================

                val message =
                    withContext(
                        Dispatchers.IO
                    ) {

                        repository.sendStaticLocation(
                            latitude = latitude,
                            longitude = longitude
                        )
                    }

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                // ====================================================
                // СРАЗУ ПОКАЗЫВАЕМ ОТВЕТ СЕРВЕРА В ЧАТЕ
                // ====================================================

                val currentDeviceId =
                    repository.getDeviceId()

                addMessage(
                    message,
                    currentDeviceId
                )

                // ====================================================
                // ПРОКРУТКА ВНИЗ
                // ====================================================

                chatScroll.post {

                    if (
                        !isFinishing &&
                        !isDestroyed
                    ) {

                        chatScroll.fullScroll(
                            ScrollView.FOCUS_DOWN
                        )
                    }
                }

                Toast.makeText(
                    this@ChatActivity,
                    "Местоположение отправлено",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                if (
                    !isFinishing &&
                    !isDestroyed
                ) {

                    Toast.makeText(
                        this@ChatActivity,
                        "Не удалось отправить геопозицию: ${
                            getErrorMessage(e)
                        }",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {

                isSending =
                    false

                if (
                    !isFinishing &&
                    !isDestroyed
                ) {

                    sendButton.isEnabled =
                        true

                    attachButton.isEnabled =
                        true
                }
            }
        }
    }


// ============================================================
// LAST KNOWN LOCATION FALLBACK
// ============================================================

    private fun getLastKnownLocationForSending() {

        if (!hasLocationPermission()) {
            return
        }

        try {

            fusedLocationClient
                .lastLocation
                .addOnSuccessListener { location ->

                    if (
                        location != null
                    ) {

                        sendStaticLocationMessage(
                            location.latitude,
                            location.longitude
                        )

                    } else {

                        Toast.makeText(
                            this,
                            "Не удалось определить местоположение. Включи GPS и геолокацию.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .addOnFailureListener { error ->

                    Toast.makeText(
                        this,
                        "Не удалось получить геопозицию: ${
                            error.message ?: "неизвестная ошибка"
                        }",
                        Toast.LENGTH_LONG
                    ).show()
                }

        } catch (
            e: SecurityException
        ) {

            Toast.makeText(
                this,
                "Нет разрешения на геолокацию",
                Toast.LENGTH_LONG
            ).show()

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Ошибка геолокации: ${
                    e.message ?: "неизвестная ошибка"
                }",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
// SEND STATIC LOCATION
// ============================================================

    private fun sendLocation(
        latitude: Double,
        longitude: Double
    ) {
        if (isSending) {
            return
        }

        isSending = true

        sendButton.isEnabled = false
        attachButton.isEnabled = false

        scope.launch {
            try {

                val message =
                    withContext(Dispatchers.IO) {
                        repository.sendStaticLocation(
                            latitude,
                            longitude
                        )
                    }
                pendingLocation = null
                // СРАЗУ ПОКАЗЫВАЕМ ОТПРАВЛЕННУЮ СТАТИЧЕСКУЮ ТОЧКУ В ЧАТЕ
                val currentDeviceId =
                    repository.getDeviceId()

                addMessage(
                    message,
                    currentDeviceId
                )

                chatScroll.post {
                    chatScroll.fullScroll(
                        View.FOCUS_DOWN
                    )
                }

                Toast.makeText(
                    this@ChatActivity,
                    "Местоположение отправлено",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                if (e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException) {
                    enqueueOffline(OfflineQueueItem(
                        id = System.currentTimeMillis().toString(),
                        type = QUEUE_LOCATION,
                        latitude = latitude,
                        longitude = longitude
                    ))
                    Toast.makeText(this@ChatActivity, "Нет интернета. Геопозиция сохранена в очередь.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ChatActivity, getErrorMessage(e), Toast.LENGTH_LONG).show()
                }
            } finally {
                isSending = false

                if (
                    !isFinishing &&
                    !isDestroyed
                ) {
                    sendButton.isEnabled = true
                    attachButton.isEnabled = true
                }
            }
        }
    }

    // ============================================================
    // MAP
    // ============================================================

    private fun openMapPicker() {

        try {

            val intent =
                Intent(
                    this,
                    MapWebViewActivity::class.java
                )

            startActivityForResult(
                intent,
                REQUEST_MAP_PICKER
            )

        } catch (
            e: Exception
        ) {

            Toast.makeText(
                this,
                "Не удалось открыть карту: ${
                    e.message
                }",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // LIVE LOCATION
    // ============================================================

    private fun startLiveLocation() {

        if (isLiveLocationActive) {

            Toast.makeText(
                this,
                "Передача геопозиции уже запущена",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (!hasLocationPermission()) {

            requestLocationPermission()

            return
        }

        closeAttachmentPanel()

        scope.launch {

            try {

                val message =
                    withContext(
                        Dispatchers.IO
                    ) {

                        repository
                            .startLiveLocation()
                    }

                liveLocationMessageId =
                    message.message_id

                // ========================================================
// СРАЗУ ПОКАЗЫВАЕМ LIVE LOCATION В ЧАТЕ
// ========================================================

                val currentDeviceId =
                    repository.getDeviceId()

                addMessage(
                    message,
                    currentDeviceId
                )

                chatScroll.post {

                    chatScroll.fullScroll(
                        View.FOCUS_DOWN
                    )
                }

                isLiveLocationActive =
                    true

                Toast.makeText(
                    this@ChatActivity,
                    "Геопозиция передаётся 15 минут",
                    Toast.LENGTH_LONG
                ).show()

                startLiveLocationUpdates(
                    message.message_id
                )

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                Toast.makeText(
                    this@ChatActivity,
                    getErrorMessage(e),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============================================================
    // LIVE LOCATION UPDATES
    // ============================================================

    private fun startLiveLocationUpdates(
        messageId: String
    ) {

        liveLocationJob?.cancel()

        liveLocationJob =
            scope.launch {

                val startedAt =
                    System.currentTimeMillis()

                while (
                    isLiveLocationActive &&
                    System.currentTimeMillis() -
                    startedAt <
                    LIVE_LOCATION_DURATION_MS
                ) {

                    sendCurrentLivePoint(
                        messageId
                    )

                    delay(
                        LIVE_LOCATION_INTERVAL_MS
                    )
                }

                if (
                    isLiveLocationActive &&
                    liveLocationMessageId ==
                    messageId
                ) {

                    stopLiveLocationInternal(
                        messageId
                    )
                }
            }
    }
    private fun stopLiveLocationManually(messageId: String) {
        if (!isLiveLocationActive) return
        if (liveLocationMessageId != messageId) return

        scope.launch {
            try {
                // 1. Останавливаем на сервере
                repository.stopLiveLocation(messageId)

                // 2. Отменяем локальный цикл
                liveLocationJob?.cancel()
                isLiveLocationActive = false
                liveLocationMessageId = null

                // 3. Обновляем UI прямо сейчас
                runOnUiThread {
                    // Меняем статус
                    liveStatusViews[messageId]?.apply {
                        text = "Передача завершена"
                        setTextColor(Color.rgb(110, 120, 130))
                    }
                    // Убираем кнопку
                    liveStopButtons[messageId]?.let { button ->
                        (button.parent as? ViewGroup)?.removeView(button)
                    }
                    liveStopButtons.remove(messageId)

                    // Показываем Toast
                    Toast.makeText(
                        this@ChatActivity,
                        "Трансляция остановлена",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@ChatActivity,
                        "Не удалось остановить трансляцию: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // SEND LIVE POINT
    // ============================================================

    private suspend fun sendCurrentLivePoint(
        messageId: String
    ) {

        if (!hasLocationPermission()) {
            return
        }

        var location:
                Location? = null

        val providers =
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

        for (provider in providers) {

            try {

                val candidate =
                    locationManager
                        .getLastKnownLocation(
                            provider
                        )

                if (
                    candidate != null &&
                    (
                            location == null ||
                                    candidate.time >
                                    location!!.time
                            )
                ) {

                    location =
                        candidate
                }

            } catch (
                _: SecurityException
            ) {
            }
        }

        val current =
            location ?: return

        try {

            withContext(
                Dispatchers.IO
            ) {

                repository
                    .sendLiveLocationPoint(
                        messageId =
                            messageId,

                        latitude =
                            current.latitude,

                        longitude =
                            current.longitude
                    )
            }

        } catch (
            _: Exception
        ) {
        }
    }

    // ============================================================
    // STOP LIVE LOCATION
    // ============================================================

    private suspend fun stopLiveLocationInternal(
        messageId: String
    ) {

        try {

            withContext(
                Dispatchers.IO
            ) {

                repository
                    .stopLiveLocation(
                        messageId
                    )
            }

        } catch (
            _: Exception
        ) {
        }

        isLiveLocationActive =
            false

        liveLocationMessageId =
            null

        liveLocationJob =
            null

        if (
            !isFinishing &&
            !isDestroyed
        ) {

            Toast.makeText(
                this@ChatActivity,
                "Передача геопозиции завершена",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
// PERMISSIONS
// ============================================================


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        // ========================================================
        // LOCATION
        // ========================================================

        if (
            requestCode == REQUEST_LOCATION
        ) {

            val granted =
                grantResults.any {
                    it ==
                            PackageManager.PERMISSION_GRANTED
                }

            if (granted) {

                // После получения разрешения
                // продолжаем отправку текущей геопозиции.

                sendCurrentLocation()

            } else {

                Toast.makeText(
                    this,
                    "Разрешение на геолокацию не предоставлено",
                    Toast.LENGTH_LONG
                ).show()
            }

            return
        }

        // ========================================================
        // PHOTOS
        // ========================================================

        if (
            requestCode == REQUEST_PHOTOS_PERMISSION
        ) {

            if (
                hasPhotoPermission()
            ) {

                if (
                    attachmentPopupRoot != null
                ) {

                    buildPopupPhotoPanel()

                    loadGalleryPreview()
                }

            } else {

                Toast.makeText(
                    this,
                    "Разреши доступ к фотографиям, чтобы показать галерею",
                    Toast.LENGTH_LONG
                ).show()
            }

            return
        }

        // ========================================================
// CAMERA (фото или видео)
// ========================================================

        if (requestCode == REQUEST_CAMERA) {

            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }

            if (granted) {
                // Проверяем, что именно мы хотели снимать
                if (pendingCameraAction == "video") {
                    openVideoCamera()
                } else {
                    openCamera()
                }
                pendingCameraAction = null
            } else {
                Toast.makeText(
                    this,
                    "Разрешение на камеру не предоставлено",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
    }
    // ============================================================
    // LOAD MESSAGES
    // ============================================================

    private fun loadMessages() {

        scope.launch {

            try {

                val messages =
                    withContext(
                        Dispatchers.IO
                    ) {

                        repository
                            .getMessages(null)
                    }

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                renderMessages(
                    messages,
                    true
                )

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                Toast.makeText(
                    this@ChatActivity,
                    getErrorMessage(e),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // POLLING
    // ============================================================

    private fun startPolling() {

        pollingJob?.cancel()

        pollingJob =
            scope.launch {

                while (true) {

                    delay(
                        3000L
                    )

                    if (
                        isFinishing ||
                        isDestroyed
                    ) {
                        break
                    }
                    flushOfflineQueue()
                    loadNewMessages()
                }
            }
    }

    private suspend fun loadNewMessages() {

        if (isSending) {
            return
        }

        try {

            val messages =
                withContext(
                    Dispatchers.IO
                ) {

                    repository
                        .getMessages(null)
                }

            if (
                isFinishing ||
                isDestroyed
            ) {
                return
            }

            renderMessages(
                messages,
                false
            )

        } catch (
            e: CancellationException
        ) {

            throw e

        } catch (
            _: Exception
        ) {
        }
    }

    // ============================================================
// RENDER MESSAGES
// ============================================================

    private fun renderMessages(
        messages: List<MessageResponse>,
        scrollToBottom: Boolean
    ) {
        if (isFinishing || isDestroyed) return

        // Запоминаем, был ли пользователь внизу чата до обновления
        val child = chatScroll.getChildAt(0)
        val wasAtBottom = child != null && chatScroll.scrollY + chatScroll.height >= child.bottom - dp(50)

        // ========================================================
        // СОЗДАЁМ СИГНАТУРУ СООБЩЕНИЙ
        // Если с сервера пришёл тот же самый список,
        // ничего не перерисовываем.
        // ========================================================

        val signature = messages.joinToString("|") { message ->
            buildString {
                append(message.message_id)
                append(":")
                append(message.created_at)
                append(":")
                append(message.text)
                append(":")
                append(message.message_type)
                append(":")
                append(message.delivered_at)
            }
        }

        if (signature == lastMessagesSignature && messagesContainer.childCount > 0) {
            // Сообщения не изменились – просто прокручиваем, если нужно
            if (scrollToBottom) {
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }, 50L)
            }
            return
        }

        lastMessagesSignature = signature

        // ========================================================
        // ПОЛНАЯ ПЕРЕРИСОВКА (список изменился)
        // ========================================================

        statusViews.clear()
        liveStatusViews.clear()
        liveStopButtons.clear()
        messagesContainer.removeAllViews()

        var previousDate: String? = null
        val currentDeviceId = repository.getDeviceId()

        for (message in messages) {
            val dateKey = getDateKey(message.created_at)
            if (!dateKey.isNullOrEmpty() && dateKey != previousDate) {
                addDateSeparator(message.created_at)
                previousDate = dateKey
            }
            addMessage(message, currentDeviceId)
        }

        // ========================================================
        // ПРОКРУТКА ВНИЗ
        // ========================================================

        // Прокручиваем, если явно запрошено (scrollToBottom = true)
        // ИЛИ если пользователь уже был внизу до обновления (wasAtBottom).
        val shouldScroll = scrollToBottom || wasAtBottom

        if (shouldScroll) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }, 100L)
        }
    }

    // ============================================================
    // DATE SEPARATOR
    // ============================================================

    private fun addDateSeparator(
        createdAt: String?
    ) {

        val date =
            parseServerDate(
                createdAt
            ) ?: return

        val dateView =
            TextView(this)

        dateView.text =
            formatDate(date)

        dateView.textSize =
            13f

        dateView.setTextColor(
            Color.rgb(
                105,
                125,
                145
            )
        )

        dateView.typeface =
            Typeface.create(
                "sans",
                Typeface.BOLD
            )

        dateView.gravity =
            Gravity.CENTER

        dateView.setPadding(
            dp(14),
            dp(6),
            dp(14),
            dp(6)
        )

        val background =
            GradientDrawable()

        background.setColor(
            Color.rgb(
                225,
                235,
                244
            )
        )

        background.cornerRadius =
            dp(18).toFloat()

        dateView.background =
            background

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        params.gravity =
            Gravity.CENTER_HORIZONTAL

        params.topMargin =
            dp(8)

        params.bottomMargin =
            dp(8)

        messagesContainer.addView(
            dateView,
            params
        )
    }

    // ============================================================
    // MESSAGE
    // ============================================================

    private fun addMessage(
        message: MessageResponse,
        currentDeviceId: String?
    ) {

        val isMine =
            !currentDeviceId.isNullOrBlank() &&
                    message.observer_device_id ==
                    currentDeviceId

        val wrapper =
            LinearLayout(this)

        wrapper.orientation =
            LinearLayout.VERTICAL

        wrapper.gravity =
            if (isMine) {
                Gravity.END
            } else {
                Gravity.START
            }

        val bubble =
            LinearLayout(this)

        bubble.orientation =
            LinearLayout.VERTICAL

        bubble.setPadding(
            dp(13),
            dp(9),
            dp(10),
            dp(6)
        )

        val background =
            GradientDrawable()

        if (isMine) {

            background.setColor(
                Color.rgb(
                    224,
                    237,
                    255
                )
            )

            background.cornerRadii =
                floatArrayOf(
                    dp(18).toFloat(),
                    dp(18).toFloat(),

                    dp(5).toFloat(),
                    dp(5).toFloat(),

                    dp(18).toFloat(),
                    dp(18).toFloat(),

                    dp(18).toFloat(),
                    dp(18).toFloat()
                )

        } else {

            background.setColor(
                Color.WHITE
            )

            background.cornerRadii =
                floatArrayOf(
                    dp(5).toFloat(),
                    dp(5).toFloat(),

                    dp(18).toFloat(),
                    dp(18).toFloat(),

                    dp(18).toFloat(),
                    dp(18).toFloat(),

                    dp(18).toFloat(),
                    dp(18).toFloat()
                )
        }

        bubble.background =
            background

        // ========================================================
// MESSAGE CONTENT
// ========================================================

        val messageType =
            message.message_type
                ?.uppercase(Locale.ROOT)

        when {

            // ====================================================
            // STATIC LOCATION
            // ====================================================

            message.static_location != null &&
                    (
                            messageType == "STATIC_LOCATION" ||
                                    messageType == "LOCATION" ||
                                    messageType == "GEOLOCATION"
                            ) -> {

                addLocationBubbleContent(
                    bubble,
                    message
                )
            }

            // ====================================================
            // LIVE LOCATION
            // ====================================================

            messageType == "LIVE_LOCATION" ||
                    messageType == "LIVE_GEOLOCATION" ||
                    messageType == "LIVE_LOCATION_START" -> {

                addLiveLocationBubbleContent(
                    bubble,
                    message,
                    isMine
                )
            }

            // ====================================================
            // MEDIA
            // ====================================================

            message.media != null -> {

                addMediaBubbleContent(
                    bubble,
                    message
                )
            }

            // ====================================================
            // TEXT
            // ====================================================

            else -> {

                val text =
                    TextView(this)

                text.text =
                    message.text ?: ""

                text.textSize =
                    16f

                text.setTextColor(
                    Color.rgb(
                        32,
                        32,
                        32
                    )
                )

                text.setLineSpacing(
                    0f,
                    1.05f
                )

                bubble.addView(
                    text
                )
            }
        }
        // ========================================================
        // META
        // ========================================================

        val meta =
            LinearLayout(this)

        meta.orientation =
            LinearLayout.HORIZONTAL

        meta.gravity =
            Gravity.CENTER_VERTICAL

        val time =
            TextView(this)

        val parsedDate =
            parseServerDate(
                message.created_at
            )

        time.text =
            if (parsedDate != null) {
                formatTime(parsedDate)
            } else {
                ""
            }

        time.textSize =
            11f

        time.setTextColor(
            Color.rgb(
                120,
                130,
                140
            )
        )


        time.includeFontPadding =
            false

        meta.addView(
            time
        )

        if (isMine) {

            val status =
                TextView(this)

            status.text =
                if (
                    message.delivered_at != null
                ) {
                    "  ✓✓"
                } else {
                    "  ✓"
                }

            status.textSize =
                12f

            status.typeface =
                Typeface.DEFAULT_BOLD

            status.includeFontPadding =
                false

            status.setTextColor(
                if (
                    message.delivered_at != null
                ) {
                    Color.rgb(
                        23,
                        100,
                        200
                    )
                } else {
                    Color.rgb(
                        120,
                        130,
                        140
                    )
                }
            )

            meta.addView(
                status
            )
            statusViews[message.message_id] = status
        }

        val metaParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(18)
            )

        metaParams.gravity =
            Gravity.END

        metaParams.topMargin =
            dp(3)

        bubble.addView(
            meta,
            metaParams
        )

        // ========================================================
        // BUBBLE SIZE
        // ========================================================

        val bubbleParams =
            LinearLayout.LayoutParams(
                (
                        resources.displayMetrics.widthPixels *
                                0.78f
                        ).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        bubbleParams.gravity =
            if (isMine) {
                Gravity.END
            } else {
                Gravity.START
            }

        bubbleParams.topMargin =
            dp(3)

        bubbleParams.bottomMargin =
            dp(3)

        wrapper.addView(
            bubble,
            bubbleParams
        )

        val wrapperParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        wrapperParams.topMargin =
            dp(1)

        wrapperParams.bottomMargin =
            dp(1)

        messagesContainer.addView(
            wrapper,
            wrapperParams
        )
    }

    // ============================================================
// MEDIA MESSAGE
// ============================================================

    private fun addMediaBubbleContent(
        bubble: LinearLayout,
        message: MessageResponse
    ) {
        val media = message.media ?: return
        val messageId = message.message_id
        if (messageId.isBlank()) return

        val mime = media.mime_type.orEmpty().lowercase(Locale.US)
        if (mime.startsWith("video/")) {
            addVideoBubbleContent(bubble, messageId)
            return
        }

        val imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.adjustViewBounds = true
        imageView.layoutParams = LinearLayout.LayoutParams(dp(250), dp(250)).apply {
            gravity = Gravity.CENTER
        }
        imageView.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(16).toFloat()
        }
        imageView.clipToOutline = true

        mediaBitmapCache.get(messageId)?.let { cached ->
            if (!cached.isRecycled) imageView.setImageBitmap(cached)
        } ?: run {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            loadMediaIntoImageView(messageId, imageView)
        }

        bubble.addView(imageView)
        imageView.setOnClickListener { openMediaFullscreen(messageId) }
    }

    private fun addVideoBubbleContent(
        bubble: LinearLayout,
        messageId: String
    ) {
        // Контейнер для видео и превью
        val container = FrameLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(dp(250), dp(250)).apply {
            gravity = Gravity.CENTER
        }
        container.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(16).toFloat()
        }
        container.clipToOutline = true

        // VideoView (скрыт до клика)
        val videoView = VideoView(this)
        videoView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        videoView.setMediaController(MediaController(this))
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            player.setVolume(1f, 1f)
        }
        container.addView(videoView)

        // ImageView для превью (первый кадр)
        val previewImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(android.R.drawable.ic_menu_gallery) // временно, пока загружается кадр
        }
        container.addView(previewImageView)

        // Иконка Play в центре (поверх превью)
        val playIcon = TextView(this).apply {
            text = "▶"
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(playIcon)

        // Текст длительности (в правом нижнем углу)
        val durationText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(8), 0, dp(8), dp(8))
            }
        }
        container.addView(durationText)

        bubble.addView(container)

        // Функция загрузки видео и превью
        fun loadVideo(file: File) {
            videoView.setVideoPath(file.absolutePath)

            // Получаем длительность
            scope.launch {
                val duration = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            retriever.release()
                            val seconds = (ms / 1000) % 60
                            val minutes = (ms / 1000) / 60
                            String.format("%02d:%02d", minutes, seconds)
                        } catch (e: Exception) {
                            "00:00"
                        }
                    }.getOrNull() ?: "00:00"
                }
                if (!isFinishing && !isDestroyed) {
                    durationText.text = duration
                }
            }

            // Получаем первый кадр для превью
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                }
                if (!isFinishing && !isDestroyed && bitmap != null) {
                    previewImageView.setImageBitmap(bitmap)
                }
            }
        }

        // Загружаем из кэша или скачиваем
        val cached = mediaFileCache[messageId]
        if (cached != null && cached.exists()) {
            loadVideo(cached)
        } else {
            if (!mediaFileLoading.contains(messageId)) {
                mediaFileLoading.add(messageId)
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            repository.downloadMediaToFile(messageId)
                        }
                        if (file != null && file.exists()) {
                            mediaFileCache[messageId] = file
                            if (!isFinishing && !isDestroyed) {
                                loadVideo(file)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("MEDIA_DEBUG", "Ошибка загрузки видео $messageId", e)
                    } finally {
                        mediaFileLoading.remove(messageId)
                    }
                }
            }
        }

        // Клик по контейнеру → открываем полноэкранный режим
        container.setOnClickListener {
            openVideoFullscreen(messageId)
        }
    }

    // Вспомогательная функция для извлечения и отображения длительности
    private fun showVideoDuration(file: File, textView: TextView) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            val formatted = String.format("%02d:%02d", minutes, seconds)
            textView.text = formatted
        } catch (e: Exception) {
            Log.e("MEDIA_DEBUG", "Ошибка получения длительности", e)
        }
    }
    private fun openVideoFullscreen(messageId: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val videoView = VideoView(this)
        videoView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        videoView.setMediaController(MediaController(this))
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            player.setVolume(1f, 1f)
            player.start()
        }
        dialog.setContentView(videoView)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()

        // Загружаем видео из кэша или скачиваем
        val cached = mediaFileCache[messageId]
        if (cached != null && cached.exists()) {
            videoView.setVideoPath(cached.absolutePath)
            videoView.start()
        } else {
            // Если не загружено, скачиваем для полноэкранного просмотра
            if (!mediaFileLoading.contains(messageId)) {
                mediaFileLoading.add(messageId)
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            repository.downloadMediaToFile(messageId)
                        }
                        if (file != null && file.exists()) {
                            mediaFileCache[messageId] = file
                            if (!isFinishing && !isDestroyed && videoView.isAttachedToWindow) {
                                videoView.setVideoPath(file.absolutePath)
                                videoView.start()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MEDIA_DEBUG", "Ошибка загрузки для полноэкранного видео", e)
                    } finally {
                        mediaFileLoading.remove(messageId)
                    }
                }
            }
        }
    }

// ============================================================
// LOAD MEDIA INTO IMAGE VIEW
// ============================================================

    private fun loadMediaIntoImageView(
        messageId: String,
        imageView: ImageView
    ) {

        // --------------------------------------------------------
        // Если эта фотография уже загружается,
        // второй запрос НЕ создаём.
        // --------------------------------------------------------

        if (
            mediaLoading.contains(
                messageId
            )
        ) {
            return
        }

        mediaLoading.add(
            messageId
        )

        scope.launch {

            try {

                val bitmap =
                    loadMediaBitmap(
                        messageId
                    )

                if (
                    bitmap != null &&
                    !bitmap.isRecycled
                ) {

                    // ------------------------------------------------
                    // КЭШ
                    // ------------------------------------------------

                    mediaBitmapCache.put(
                        messageId,
                        bitmap
                    )

                    // ------------------------------------------------
                    // UI МЕНЯЕМ ТОЛЬКО НА MAIN
                    // ------------------------------------------------

                    if (
                        !isFinishing &&
                        !isDestroyed &&
                        imageView.isAttachedToWindow
                    ) {

                        imageView.setImageBitmap(
                            bitmap
                        )
                    }
                }

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: OutOfMemoryError
            ) {

                Log.e(
                    "MEDIA_DEBUG",
                    "Недостаточно памяти при отображении $messageId",
                    e
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MEDIA_DEBUG",
                    "Ошибка отображения MEDIA: $messageId",
                    e
                )

            } finally {

                mediaLoading.remove(
                    messageId
                )
            }
        }
    }
// ============================================================
// LOAD MEDIA BITMAP
// ============================================================

    // ============================================================
// LOAD MEDIA BITMAP
// ============================================================

    private suspend fun loadMediaBitmap(
        messageId: String
    ): Bitmap? {

        return try {

            withContext(Dispatchers.IO) {

                Log.d(
                    "MEDIA_DEBUG",
                    "Начинаю загрузку: $messageId"
                )

                val bytes =
                    repository.downloadMedia(
                        messageId
                    )

                Log.d(
                    "MEDIA_DEBUG",
                    "Получено байт: ${bytes.size}"
                )

                if (bytes.isEmpty()) {

                    Log.e(
                        "MEDIA_DEBUG",
                        "Сервер вернул пустой файл"
                    )

                    return@withContext null
                }

                // ====================================================
                // Сначала узнаём размер изображения
                // ====================================================

                val boundsOptions =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    boundsOptions
                )

                val originalWidth =
                    boundsOptions.outWidth

                val originalHeight =
                    boundsOptions.outHeight

                Log.d(
                    "MEDIA_DEBUG",
                    "Размер оригинала: " +
                            "${originalWidth}x${originalHeight}"
                )

                if (
                    originalWidth <= 0 ||
                    originalHeight <= 0
                ) {

                    Log.e(
                        "MEDIA_DEBUG",
                        "Не удалось определить размер изображения"
                    )

                    return@withContext null
                }

                // ====================================================
                // Ограничиваем размер Bitmap.
                //
                // Нам не нужен огромный оригинал 3072x4080,
                // потому что в чате картинка всё равно около 250dp.
                // ====================================================

                val maxWidth = 768
                val maxHeight = 1024

                var sampleSize = 1

                while (
                    originalWidth / sampleSize > maxWidth ||
                    originalHeight / sampleSize > maxHeight
                ) {

                    sampleSize *= 2
                }

                Log.d(
                    "MEDIA_DEBUG",
                    "inSampleSize = $sampleSize"
                )

                // ====================================================
                // Декодируем УЖЕ В IO
                // ====================================================

                val decodeOptions =
                    BitmapFactory.Options().apply {

                        inSampleSize =
                            sampleSize

                        inPreferredConfig =
                            Bitmap.Config.RGB_565

                        inDither =
                            true
                    }

                val bitmap =
                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        decodeOptions
                    )

                if (bitmap != null) {

                    Log.d(
                        "MEDIA_DEBUG",
                        "Bitmap создан: " +
                                "${bitmap.width}x${bitmap.height}, " +
                                "bytes=${bitmap.allocationByteCount}"
                    )
                } else {

                    Log.e(
                        "MEDIA_DEBUG",
                        "BitmapFactory вернул null"
                    )
                }

                bitmap
            }

        } catch (
            e: CancellationException
        ) {

            throw e

        } catch (
            e: Exception
        ) {

            Log.e(
                "MEDIA_DEBUG",
                "Ошибка загрузки MEDIA: $messageId",
                e
            )

            null
        }
    }

    // ============================================================
    // FULLSCREEN MEDIA
    // ============================================================

    private fun openMediaFullscreen(
        messageId: String
    ) {

        val dialog =
            Dialog(this)

        val imageView =
            ImageView(this)

        imageView.setBackgroundColor(
            Color.BLACK
        )

        imageView.scaleType =
            ImageView.ScaleType.FIT_CENTER

        dialog.setContentView(
            imageView
        )

        dialog.setOnShowListener {

            dialog.window?.setBackgroundDrawable(
                ColorDrawable(
                    Color.BLACK
                )
            )

            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        imageView.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        scope.launch {

            val bitmap =
                loadMediaBitmap(
                    messageId
                )

            if (
                bitmap != null &&
                !isFinishing &&
                !isDestroyed
            ) {

                imageView.setImageBitmap(
                    bitmap
                )
            }
        }
    }

    // ============================================================
    // LOCATION BUBBLE
    // ============================================================

    private fun addLocationBubbleContent(
        bubble: LinearLayout,
        message: MessageResponse
    ) {

        val location =
            message.static_location
                ?: return

        val locationContainer =
            LinearLayout(this)

        locationContainer.orientation =
            LinearLayout.VERTICAL

        locationContainer.gravity =
            Gravity.CENTER_HORIZONTAL

        val icon =
            TextView(this)

        icon.text =
            "⌖"

        icon.textSize =
            38f

        icon.gravity =
            Gravity.CENTER

        icon.setTextColor(
            Color.WHITE
        )

        val iconBackground =
            GradientDrawable()

        iconBackground.setColor(
            Color.rgb(
                45,
                130,
                220
            )
        )

        iconBackground.shape =
            GradientDrawable.OVAL

        icon.background =
            iconBackground

        val iconParams =
            LinearLayout.LayoutParams(
                dp(68),
                dp(68)
            )

        locationContainer.addView(
            icon,
            iconParams
        )

        val title =
            TextView(this)

        title.text =
            "Местоположение"

        title.textSize =
            15f

        title.typeface =
            Typeface.DEFAULT_BOLD

        title.setTextColor(
            Color.rgb(
                35,
                35,
                35
            )
        )

        title.gravity =
            Gravity.CENTER

        val titleParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

        titleParams.topMargin =
            dp(8)

        locationContainer.addView(
            title,
            titleParams
        )

        val coordinates =
            TextView(this)

        coordinates.text =
            String.format(
                Locale.US,
                "%.6f, %.6f",
                location.latitude,
                location.longitude
            )

        coordinates.textSize =
            12f

        coordinates.setTextColor(
            Color.rgb(
                110,
                120,
                130
            )
        )

        coordinates.gravity =
            Gravity.CENTER

        locationContainer.addView(
            coordinates
        )

        bubble.addView(
            locationContainer
        )

        locationContainer.setOnClickListener {

            val uri =
                Uri.parse(
                    "geo:${location.latitude},${location.longitude}" +
                            "?q=${location.latitude},${location.longitude}"
                )

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                )

            try {

                startActivity(
                    intent
                )

            } catch (
                _: Exception
            ) {

                Toast.makeText(
                    this,
                    "На устройстве нет приложения карт",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
// LIVE LOCATION BUBBLE
// ============================================================

    private fun addLiveLocationBubbleContent(
        bubble: LinearLayout,
        message: MessageResponse,
        isMine: Boolean   // <-- добавили параметр
    ) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.setPadding(dp(4), dp(4), dp(4), dp(4))

        // Иконка
        val icon = TextView(this).apply {
            text = "📡"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val iconBackground = GradientDrawable().apply {
                setColor(Color.rgb(45, 130, 220))
                shape = GradientDrawable.OVAL
            }
            background = iconBackground
        }
        container.addView(icon, LinearLayout.LayoutParams(dp(68), dp(68)))

        // Заголовок
        val title = TextView(this).apply {
            text = "Передача геопозиции"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 35, 35))
            gravity = Gravity.CENTER
        }
        val titleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        container.addView(title, titleParams)

        // Статус
        val status = TextView(this).apply {
            text = if (isLiveLocationActive && message.message_id == liveLocationMessageId) {
                "● Геопозиция передаётся"
            } else {
                "Передача завершена"
            }
            textSize = 12f
            setTextColor(
                if (isLiveLocationActive && message.message_id == liveLocationMessageId) {
                    Color.rgb(45, 150, 90)
                } else {
                    Color.rgb(110, 120, 130)
                }
            )

            gravity = Gravity.CENTER
        }
        liveStatusViews[message.message_id] = status
        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        container.addView(status, statusParams)

        // Описание
        val description = TextView(this).apply {
            text = "Передача в течение 15 минут"
            textSize = 11f
            setTextColor(Color.rgb(125, 130, 140))
            gravity = Gravity.CENTER
        }
        val descParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }
        container.addView(description, descParams)

        // ============================================================
        // КНОПКА ОСТАНОВКИ (только для своих активных трансляций)
        // ============================================================
        if (isMine && isLiveLocationActive && message.message_id == liveLocationMessageId) {
            val stopButton = TextView(this).apply {
                text = "Остановить"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(200, 50, 50))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(200, 50, 50))
                    cornerRadius = dp(20).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    gravity = Gravity.CENTER
                    topMargin = dp(8)
                }
                setOnClickListener { stopLiveLocationManually(message.message_id) }
            }
            liveStopButtons[message.message_id] = stopButton
            container.addView(stopButton)   // ТОЛЬКО ОДИН РАЗ
        }


        bubble.addView(container)
    }

    // ============================================================
    // SEND TEXT
    // ============================================================

    private fun sendCurrentMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || isSending) return

        pendingText = text  // сохраняем для повторной отправки

        isSending = true
        sendButton.isEnabled = false
        attachButton.isEnabled = false
        messageInput.isEnabled = false

        scope.launch {
            try {
                repository.sendMessage(text)

                // Успешно — очищаем pending
                pendingText = null

                // Показываем Toast о первом сообщении (если нужно)
                if (!prefs.getBoolean("first_message_shown", false)) {
                    Toast.makeText(
                        this@ChatActivity,
                        "Спасибо за обращение. Оно уже было передано инспекторам.",
                        Toast.LENGTH_LONG
                    ).show()
                    prefs.edit().putBoolean("first_message_shown", true).apply()
                }

                // Показываем контекстные кнопки после первого сообщения
                if (!prefs.getBoolean("context_buttons_shown", false)) {
                    contextButtonsContainer.visibility = View.VISIBLE
                    prefs.edit().putBoolean("context_buttons_shown", true).apply()
                }

                if (isFinishing || isDestroyed) return@launch
                messageInput.text.clear()
                val messages = withContext(Dispatchers.IO) {
                    repository.getMessages(null)
                }
                renderMessages(messages, true)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                val isNetworkError = e is IOException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException
                if (isNetworkError) {
                    enqueueOffline(OfflineQueueItem(
                        id = System.currentTimeMillis().toString(),
                        type = QUEUE_TEXT,
                        text = text
                    ))
                    messageInput.text.clear()
                    Toast.makeText(this@ChatActivity, "Нет интернета. Сообщение сохранено в очередь.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ChatActivity, getErrorMessage(e), Toast.LENGTH_LONG).show()
                }
            } finally {
                isSending = false
                if (!isFinishing && !isDestroyed) {
                    sendButton.isEnabled = true
                    attachButton.isEnabled = true
                    messageInput.isEnabled = true
                }
            }
        }
    }

    // ============================================================
    // DATE PARSER
    // ============================================================

    private fun parseServerDate(value: String?): ZonedDateTime? {
        if (value.isNullOrBlank()) return null
        val clean = value.trim()

        try {
            return Instant.parse(clean).atZone(MOSCOW_ZONE)
        } catch (_: Exception) {}

        try {
            return OffsetDateTime.parse(clean).atZoneSameInstant(MOSCOW_ZONE)
        } catch (_: Exception) {}

        try {
            val normalized = clean.replace(" ", "T")
            return OffsetDateTime.parse(normalized).atZoneSameInstant(MOSCOW_ZONE)
        } catch (_: Exception) {}

        val localFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (pattern in localFormats) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
                val localDateTime = java.time.LocalDateTime.parse(clean, formatter)
                return localDateTime.atZone(MOSCOW_ZONE)
            } catch (_: DateTimeParseException) {}
        }

        try {
            val date = LocalDate.parse(clean)
            return date.atStartOfDay(MOSCOW_ZONE)
        } catch (_: Exception) {}

        return null
    }

    // ============================================================
    // TIME
    // ============================================================

    private fun formatTime(date: ZonedDateTime): String {
        return date.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    }

    private fun formatDate(date: ZonedDateTime): String {
        val today = LocalDate.now(MOSCOW_ZONE)
        val messageDate = date.toLocalDate()
        return when {
            messageDate == today -> "Сегодня"
            messageDate == today.minusDays(1) -> "Вчера"
            else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru", "RU")))
        }
    }

    // ============================================================
    // DATE KEY
    // ============================================================

    private fun getDateKey(
        value: String?
    ): String? {

        val date =
            parseServerDate(
                value
            ) ?: return null

        return date
            .toLocalDate()
            .toString()
    }

    // ============================================================
    // ERROR
    // ============================================================

    private fun getErrorMessage(
        throwable: Throwable
    ): String {

        return throwable.message
            ?.takeIf {
                it.isNotBlank()
            }
            ?: "Ошибка соединения с сервером"
    }

    // ============================================================
    // DP
    // ============================================================

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {
        webSocketManager?.disconnect()
        webSocketManager = null

        attachmentPopup?.dismiss()

        attachmentPopup =
            null

        pollingJob?.cancel()

        liveLocationJob?.cancel()

        isLiveLocationActive =
            false

        try {

            locationListener?.let {

                locationManager
                    .removeUpdates(it)
            }

        } catch (
            _: Exception
        ) {
        }

        locationListener =
            null

        handler.removeCallbacksAndMessages(
            null
        )
        locationListener?.let {

            try {

                locationManager.removeUpdates(
                    it
                )

            } catch (_: Exception) {
            }
        }

        locationListener = null
        scope.cancel()

        super.onDestroy()
    }
}

