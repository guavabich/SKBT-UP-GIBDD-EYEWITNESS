package com.example.gibddochevidets

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashActivity : ComponentActivity() {

    private val splashTime = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)

        // Цвет статус-бара
        window.statusBarColor = getColor(R.color.background_light)

        // Темные системные значки
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = findViewById<View>(R.id.splashRoot)

        // Учитываем статус-бар
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                0,
                systemBars.top,
                0,
                0
            )

            insets
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({

            val intent = android.content.Intent(
                this,
                MainActivity::class.java
            )

            startActivity(intent)

            finish()

        }, splashTime)
    }
}