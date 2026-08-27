package com.example.gibddochevidets

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.gibddochevidets.network.ApiRepository
import com.example.gibddochevidets.network.BanStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IntroActivity : Activity() {

    private lateinit var repository: ApiRepository
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var skipCheckbox: CheckBox
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = ApiRepository(applicationContext)
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // Если интро уже пропущен или устройство уже зарегистрировано — запускаем чат (с проверками)
        val deviceId = repository.getDeviceId()
        if (prefs.getBoolean("skip_intro", false) ) {
            launchChatOrBan()
            return
        }

        // Иначе показываем экран интро
        createScreen()
    }

    private fun launchChatOrBan() {
        scope.launch {
            // Проверяем здоровье сервера
            val isHealthy = withContext(Dispatchers.IO) {
                repository.checkHealth()
            }
            if (!isHealthy) {
                showServerUnavailableDialog()
                return@launch
            }

            // Проверяем бан, если устройство зарегистрировано
            val deviceId = repository.getDeviceId()
            if (!deviceId.isNullOrBlank()) {
                val ban = withContext(Dispatchers.IO) {
                    runCatching { repository.getActiveBan() }.getOrNull()
                }
                if (ban != null && ban.is_banned) {
                    openBanActivity(ban)
                    return@launch
                }
            }

            // Всё хорошо — открываем чат
            startChatAndFinish()
        }
    }

    private fun startChatAndFinish() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    private fun showServerUnavailableDialog() {
        AlertDialog.Builder(this)
            .setTitle("Нет соединения с сервером")
            .setMessage("Не удалось подключиться к серверу. Проверьте интернет-соединение и попробуйте снова.")
            .setPositiveButton("Повторить") { _, _ ->
                launchChatOrBan()
            }
            .setNegativeButton("Выйти") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openBanActivity(ban: BanStatusResponse) {
        val intent = Intent(this, BanActivity::class.java)
        intent.putExtra(BanActivity.EXTRA_REASON, ban.reason)
        intent.putExtra(BanActivity.EXTRA_ENDS_AT, ban.ends_at)
        startActivity(intent)
        finish()
    }

    // ============================================================
    // BroadcastReceiver для события снятия бана
    // ============================================================

    private val banEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "BAN_ENDED") {
                launchChatOrBan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    // ============================================================
    // Обработка результата запроса разрешений
    // ============================================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Разрешение получено – можно показывать уведомления
            } else {
                // Разрешение отклонено – уведомления не будут показываться
            }
        }
    }

    // ============================================================
    // Создание экрана приветствия
    // ============================================================

    private fun createScreen() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(245, 248, 251))

        root.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                dp(20),
                systemBars.top + dp(20),
                dp(20),
                systemBars.bottom + dp(20)
            )
            insets
        }

        val scrollView = ScrollView(this)
        scrollView.isFillViewport = true
        scrollView.overScrollMode = View.OVER_SCROLL_NEVER

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL

        // --- Заголовок ---
        val title = TextView(this)
        title.text = "ГИБДД-Очевидец"
        title.textSize = 25f
        title.setTextColor(Color.rgb(25, 40, 55))
        title.typeface = Typeface.create("sans", Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setLineSpacing(0f, 1.05f)
        val titleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        titleParams.bottomMargin = dp(24)
        content.addView(title, titleParams)

        // --- Карточка с текстом ---
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(22), dp(22), dp(22), dp(22))
        val cardBackground = GradientDrawable()
        cardBackground.setColor(Color.WHITE)
        cardBackground.cornerRadius = dp(24).toFloat()
        cardBackground.setStroke(dp(1), Color.rgb(228, 234, 240))
        card.background = cardBackground

        val cardTitle = TextView(this)
        cardTitle.text = "Уважаемые участники дорожного движения!"
        cardTitle.textSize = 20f
        cardTitle.setTextColor(Color.rgb(25, 40, 55))
        cardTitle.typeface = Typeface.create("sans", Typeface.BOLD)
        val cardTitleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cardTitleParams.bottomMargin = dp(16)
        card.addView(cardTitle, cardTitleParams)

        val informationText = TextView(this)
        informationText.text = "Госавтоинспекция Костромской области информирует, " +
                "что приложение создано для предупреждения ДТП " +
                "с участием нетрезвых водителей.\n\n" +
                "С его помощью можно анонимно сообщать о водителях " +
                "с признаками опьянения, которые управляют транспортом.\n\n" +
                "В сообщении можно указать номер, марку, цвет автомобиля, " +
                "направление движения, отправить геолокацию, фото или видео.\n\n" +
                "Вся поступившая информация обрабатывается роботом."
        informationText.textSize = 16f
        informationText.setTextColor(Color.rgb(55, 65, 75))
        informationText.setLineSpacing(dp(3).toFloat(), 1.08f)
        informationText.includeFontPadding = true
        card.addView(informationText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cardParams.bottomMargin = dp(24)
        content.addView(card, cardParams)

        // --- Чекбокс "Больше не показывать" ---
        skipCheckbox = CheckBox(this)
        skipCheckbox.text = "Больше не показывать это сообщение"
        skipCheckbox.textSize = 14f
        skipCheckbox.setTextColor(Color.rgb(55, 65, 75))
        skipCheckbox.gravity = Gravity.CENTER_VERTICAL
        skipCheckbox.setPadding(0, dp(8), 0, dp(8))
        val checkBoxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        checkBoxParams.bottomMargin = dp(12)
        content.addView(skipCheckbox, checkBoxParams)

        // --- Кнопка "Начать" ---
        val startButton = TextView(this)
        startButton.text = "Начать"
        startButton.textSize = 17f
        startButton.setTextColor(Color.WHITE)
        startButton.typeface = Typeface.create("sans", Typeface.BOLD)
        startButton.gravity = Gravity.CENTER
        startButton.isClickable = true
        startButton.isFocusable = true
        startButton.setPadding(dp(20), 0, dp(20), 0)

        val buttonBackground = GradientDrawable()
        buttonBackground.setColor(Color.rgb(35, 91, 170))
        buttonBackground.cornerRadius = dp(20).toFloat()
        startButton.background = buttonBackground

        startButton.setOnClickListener {
            if (skipCheckbox.isChecked) {
                prefs.edit().putBoolean("skip_intro", true).apply()
            }
            // Переход в чат
            startActivity(Intent(this@IntroActivity, ChatActivity::class.java))
            finish()
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        )
        content.addView(startButton, buttonParams)

        // --- Добавляем всё в ScrollView и root ---
        scrollView.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        root.requestApplyInsets()
    }

    // ============================================================
    // Вспомогательный метод для dp
    // ============================================================

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}