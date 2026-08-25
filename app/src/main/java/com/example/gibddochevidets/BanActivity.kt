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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BanActivity : Activity() {

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_ENDS_AT = "ban_ends_at"
        const val EXTRA_PERMANENT = "ban_permanent"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var remainingText: TextView? = null
    private var reasonText: TextView? = null

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

        // --- Причина бана (если передана) ---
        val reason = intent.getStringExtra(EXTRA_REASON)
        if (!reason.isNullOrBlank()) {
            val reasonView = TextView(this).apply {
                text = "Причина: $reason"
                textSize = 13f
                setTextColor(Color.rgb(200, 50, 50))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            }
            banCard.addView(reasonView)
        }

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
            text = "▣"
            textSize = 25f
            setTextColor(Color.BLACK)
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

        // --- Осталось времени ---
        remainingText = TextView(this).apply {
            text = getRemainingText()
            textSize = 13f
            setTextColor(Color.rgb(23, 59, 145))
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

    private fun getBanEndText(): String {
        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
        if (permanent) {
            return "Блокировка постоянная"
        }
        val endsAt = intent.getStringExtra(EXTRA_ENDS_AT)
        if (endsAt.isNullOrBlank()) {
            return "Срок блокировки неизвестен"
        }
        val date = parseDate(endsAt)
        if (date == null) {
            return endsAt
        }
        val outputFormat = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru"))
        return outputFormat.format(date)
    }

    private fun getRemainingText(): String {
        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
        if (permanent) {
            return "Блокировка постоянная"
        }
        val endsAt = intent.getStringExtra(EXTRA_ENDS_AT)
        if (endsAt.isNullOrBlank()) {
            return "Осталось: неизвестно"
        }
        val endDate = parseDate(endsAt)
        if (endDate == null) {
            return "Осталось: неизвестно"
        }
        val remaining = endDate.time - System.currentTimeMillis()
        if (remaining <= 0) {
            return "Блокировка закончилась"
        }
        return "Осталось: ${formatRemainingTime(remaining)}"
    }

    private fun updateRemainingTime() {
        remainingText?.text = getRemainingText()
    }

    private fun formatRemainingTime(milliseconds: Long): String {
        var totalSeconds = milliseconds / 1000
        val days = totalSeconds / 86400
        totalSeconds %= 86400
        val hours = totalSeconds / 3600
        totalSeconds %= 3600
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return when {
            days > 0 -> "$days ${plural(days, "день", "дня", "дней")} $hours ${plural(hours, "час", "часа", "часов")}"
            hours > 0 -> "$hours ${plural(hours, "час", "часа", "часов")} $minutes ${plural(minutes, "минута", "минуты", "минут")}"
            minutes > 0 -> "$minutes ${plural(minutes, "минута", "минуты", "минут")} $seconds ${plural(seconds, "секунда", "секунды", "секунд")}"
            else -> "$seconds ${plural(seconds, "секунда", "секунды", "секунд")}"
        }
    }

    private fun plural(value: Long, one: String, few: String, many: String): String {
        val number = value % 100
        if (number in 11..19) {
            return many
        }
        return when (value % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    private fun parseDate(value: String): Date? {
        val inputFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        )
        for (format in inputFormats) {
            format.timeZone = TimeZone.getTimeZone("UTC")
            try {
                val date = format.parse(value)
                if (date != null) {
                    return date
                }
            } catch (_: Exception) {
            }
        }
        return null
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