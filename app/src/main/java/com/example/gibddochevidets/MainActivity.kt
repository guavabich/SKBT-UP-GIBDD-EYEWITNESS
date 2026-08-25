package com.example.gibddochevidets

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gibddochevidets.network.ApiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var logoImage: ImageView
    private lateinit var loadingText: TextView

    private val handler =
        Handler(Looper.getMainLooper())

    private val activityScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main
        )

    private lateinit var apiRepository: ApiRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        logoImage =
            findViewById(
                R.id.logoImage
            )

        loadingText =
            findViewById(
                R.id.loadingText
            )

        apiRepository =
            ApiRepository(this)

        startRegistration()
    }

    // ============================================================
    // РЕГИСТРАЦИЯ УСТРОЙСТВА
    // ============================================================

    private fun startRegistration() {

        loadingText.text =
            "Подключение..."

        activityScope.launch {

            try {

                withContext(
                    Dispatchers.IO
                ) {

                    apiRepository.registerDevice()
                }

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                loadingText.text =
                    "Готово"

                handler.postDelayed({

                    if (
                        !isFinishing &&
                        !isDestroyed
                    ) {

                        openIntro()
                    }

                }, 300)

            } catch (
                e: Exception
            ) {

                if (
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                loadingText.text =
                    "Ошибка подключения"

                Toast.makeText(
                    this@MainActivity,
                    e.message
                        ?: "Не удалось подключиться к серверу",
                    Toast.LENGTH_LONG
                ).show()

                handler.postDelayed({

                    if (
                        !isFinishing &&
                        !isDestroyed
                    ) {

                        startRegistration()
                    }

                }, 3000)
            }
        }
    }

    // ============================================================
    // ВВОДНЫЙ ЭКРАН
    // ============================================================

    private fun openIntro() {

        startActivity(
            Intent(
                this,
                IntroActivity::class.java
            )
        )

        finish()
    }

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(
            null
        )

        activityScope.cancel()

        super.onDestroy()
    }
}