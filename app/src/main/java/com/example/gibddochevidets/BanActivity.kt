package com.example.gibddochevidets

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class BanActivity : Activity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_ENDS_AT = "ban_ends_at"
        const val EXTRA_PERMANENT = "ban_permanent"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var remainingText: TextView

    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateRemainingTime()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        createScreen()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(countdownRunnable)
    }

    private fun createScreen() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(242, 248, 253))

        // --- Синий заголовок ---
        val header = TextView(this).apply {
            text = "ГИБДД-Очевидец"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(23, 59, 145), dp(4).toFloat())
        }
        val headerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
            topMargin = dp(6)
        }
        root.addView(header, headerParams)

        // --- Основной контент ---
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        val contentParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(44) }
        root.addView(content, contentParams)

        // --- Заголовок ---
        val title = TextView(this).apply {
            text = "Отправка сообщений\nвременно недоступна"
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val titleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        content.addView(title, titleParams)

        // --- Подзаголовок ---
        val subtitle = TextView(this).apply {
            text = "Ваш доступ к отправке сообщений ограничен."
            textSize = 12f
            setTextColor(Color.rgb(110, 110, 110))
            gravity = Gravity.CENTER
        }
        val subtitleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        content.addView(subtitle, subtitleParams)

        // --- Карточка бана ---
        val banCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundedStrokeBackground(
                Color.WHITE,
                Color.rgb(150, 175, 255),
                dp(6).toFloat(),
                dp(2)
            )
        }
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        content.addView(banCard, cardParams)

        // --- Текст "БЛОКИРОВКА ДЕЙСТВУЕТ ДО:" ---
        val banTitle = TextView(this).apply {
            text = "БЛОКИРОВКА ДЕЙСТВУЕТ ДО:"
            textSize = 11f
            setTextColor(Color.rgb(100, 100, 100))
            typeface = Typeface.DEFAULT_BOLD
        }
        banCard.addView(banTitle)

        // --- Дата ---
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dateParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(45)
        )
        banCard.addView(dateRow, dateParams)

        val calendar = TextView(this).apply {
            text = "!"
            textSize = 25f
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
        }
        val calendarParams = LinearLayout.LayoutParams(dp(32), dp(40))
        dateRow.addView(calendar, calendarParams)

        val dateText = TextView(this).apply {
            text = getBanEndText()
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        val dateTextParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            leftMargin = dp(4)
        }
        dateRow.addView(dateText, dateTextParams)

        // --- Оставшееся время (ИСПРАВЛЕНО) ---
        remainingText = TextView(this).apply {
            text = getRemainingText()
            textSize = 14f
            setTextColor(Color.rgb(50, 50, 50))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val remainingParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(35)
        )
        banCard.addView(remainingText, remainingParams)

        setContentView(root)
    }

    // ============================================================
    // ЛОГИКА ДАТ И ВРЕМЕНИ
    // ============================================================

    private fun getBanEndText(): String {
        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
        if (permanent) {
            return "Блокировка постоянная"
        }
        val endsAt = intent.getStringExtra(EXTRA_ENDS_AT)
        val date = parseBanEnd(endsAt)
            ?: return "Срок блокировки неизвестен"

        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))
        return date.format(formatter)
    }

    private fun getRemainingText(): String {
        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
        if (permanent) {
            return "Блокировка постоянная"
        }
        val endsAt = intent.getStringExtra(EXTRA_ENDS_AT)
        val endDate = parseBanEnd(endsAt)
            ?: return "Осталось: неизвестно"

        val now = ZonedDateTime.now()
        if (now.isAfter(endDate)) {
            return "Блокировка закончилась"
        }

        // Разбиваем время на годы, месяцы, дни, часы, минуты и секунды
        val years = ChronoUnit.YEARS.between(now, endDate)
        val months = ChronoUnit.MONTHS.between(now.plusYears(years), endDate)
        val days = ChronoUnit.DAYS.between(now.plusYears(years).plusMonths(months), endDate)
        val hours = ChronoUnit.HOURS.between(now.plusYears(years).plusMonths(months).plusDays(days), endDate)
        val minutes = ChronoUnit.MINUTES.between(now.plusYears(years).plusMonths(months).plusDays(days).plusHours(hours), endDate)
        val seconds = ChronoUnit.SECONDS.between(now.plusYears(years).plusMonths(months).plusDays(days).plusHours(hours).plusMinutes(minutes), endDate)

        val parts = mutableListOf<String>()

        if (years > 0) parts.add("$years ${plural(years, "год", "года", "лет")}")
        if (months > 0) parts.add("$months ${plural(months, "месяц", "месяца", "месяцев")}")
        if (days > 0) parts.add("$days ${plural(days, "день", "дня", "дней")}")
        if (hours > 0) parts.add("$hours ${plural(hours, "час", "часа", "часов")}")
        if (minutes > 0) parts.add("$minutes ${plural(minutes, "минута", "минуты", "минут")}")
        if (seconds > 0 || parts.isEmpty()) parts.add("$seconds ${plural(seconds, "секунда", "секунды", "секунд")}")

        return "Осталось: ${parts.joinToString(" ")}"
    }

    private fun updateRemainingTime() {
        remainingText.text = getRemainingText()
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
    // ============================================================

    private fun parseBanEnd(value: String?): ZonedDateTime? {
        if (value.isNullOrBlank()) return null
        val clean = value.trim()

        // Формат с Z (UTC)
        return try {
            Instant.parse(clean).atZone(ZoneId.systemDefault())
        } catch (_: Exception) {
            // Формат с +03:00
            try {
                OffsetDateTime.parse(clean).toZonedDateTime()
            } catch (_: Exception) {
                // Локальные форматы без зоны (берем текущую зону устройства)
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS",
                    "yyyy-MM-dd HH:mm:ss.SSS"
                )
                var parsed: LocalDateTime? = null
                for (fmt in formats) {
                    try {
                        parsed = LocalDateTime.parse(clean, DateTimeFormatter.ofPattern(fmt))
                        break
                    } catch (_: Exception) {}
                }
                parsed?.atZone(ZoneId.systemDefault())
            }
        }
    }

    private fun plural(value: Long, one: String, few: String, many: String): String {
        val number = value % 100
        if (number in 11..19) return many
        return when (value % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    private fun roundedBackground(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun roundedStrokeBackground(
        fillColor: Int,
        strokeColor: Int,
        radius: Float,
        strokeWidth: Int
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            setStroke(dp(strokeWidth), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}