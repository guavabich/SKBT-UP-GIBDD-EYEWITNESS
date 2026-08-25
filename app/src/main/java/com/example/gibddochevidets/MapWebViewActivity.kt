package com.example.gibddochevidets

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MapWebViewActivity : Activity() {

    companion object {
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        private const val DEFAULT_LATITUDE = 57.7679
        private const val DEFAULT_LONGITUDE = 40.9269
        private const val DEFAULT_ZOOM = 13
    }

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var coordinatesText: TextView
    private lateinit var selectButton: TextView

    private var selectedLatitude = DEFAULT_LATITUDE
    private var selectedLongitude = DEFAULT_LONGITUDE
    private var isFinishingBySelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет интернета", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        createUI()
    }

    private fun createUI() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        // ---------- WebView ----------
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d("MAP_PICKER", "WebView загружен")
                    view?.loadUrl("javascript:setCenter($DEFAULT_LATITUDE, $DEFAULT_LONGITUDE, $DEFAULT_ZOOM)")
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e("MAP_PICKER", "WebView error: $errorCode - $description")
                    Toast.makeText(this@MapWebViewActivity, "Ошибка загрузки карты: $description", Toast.LENGTH_LONG).show()
                }
            }

            setWebChromeClient(object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    Log.d("MAP_PICKER", "JS Console: ${consoleMessage.message()}")
                    return true
                }
            })

            addJavascriptInterface(JSInterface(), "Android")
        }

        val webParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(webView, webParams)

        // Загружаем HTML-карту
        webView.loadDataWithBaseURL(null, getMapHtml(), "text/html", "UTF-8", null)

        // ---------- Верхняя панель ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(112)
        ).apply { gravity = Gravity.TOP }
        root.addView(topBar, topParams)

        // Заголовок
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = TextView(this).apply {
            text = "‹"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setOnClickListener { finish() }
        }
        titleRow.addView(backButton, LinearLayout.LayoutParams(dp(52), dp(52)))

        val title = TextView(this).apply {
            text = "Выберите точку"
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        topBar.addView(titleRow)

        // Подсказка
        val hint = TextView(this).apply {
            text = "Переместите карту так, чтобы нужное место оказалось под красной точкой"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        topBar.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ))

        // ---------- Координаты ----------
        coordinatesText = TextView(this).apply {
            text = formatCoordinates(selectedLatitude, selectedLongitude)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.WHITE, dp(16).toFloat())
            elevation = dp(4).toFloat()
        }
        val coordsParams = FrameLayout.LayoutParams(dp(220), dp(42)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(124)
            rightMargin = dp(10)
        }
        root.addView(coordinatesText, coordsParams)

        // ---------- Красная точка ----------
        val centerPoint = TextView(this).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.rgb(220, 40, 40))
            gravity = Gravity.CENTER
            setShadowLayer(5f, 0f, 2f, Color.WHITE)
            isClickable = false
            isFocusable = false
        }
        val centerParams = FrameLayout.LayoutParams(dp(50), dp(50)).apply {
            gravity = Gravity.CENTER
        }
        root.addView(centerPoint, centerParams)

        // ---------- Кнопка "Выбрать" ----------
        selectButton = TextView(this).apply {
            text = "Выбрать эту точку"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = roundedBackground(Color.rgb(23, 59, 145), dp(18).toFloat())
            isEnabled = true
            alpha = 1f
            setOnClickListener {
                if (isFinishingBySelection) return@setOnClickListener
                webView.loadUrl("javascript:getCenter()")
            }
        }
        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(20)
        }
        root.addView(selectButton, buttonParams)

        setContentView(root)
    }

    // ---------- JavaScript-интерфейс ----------
    inner class JSInterface {
        @JavascriptInterface
        fun onCenterChanged(lat: Double, lon: Double) {
            runOnUiThread {
                selectedLatitude = lat
                selectedLongitude = lon
                coordinatesText.text = formatCoordinates(lat, lon)
                Log.d("MAP_PICKER", "Центр: $lat, $lon")
            }
        }

        @JavascriptInterface
        fun onCenterRequested(lat: Double, lon: Double) {
            runOnUiThread {
                val result = Intent().apply {
                    putExtra(EXTRA_LATITUDE, lat)
                    putExtra(EXTRA_LONGITUDE, lon)
                }
                setResult(Activity.RESULT_OK, result)
                isFinishingBySelection = true
                finish()
            }
        }
    }

    // ---------- HTML-код карты ----------
    private fun getMapHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html { margin:0; padding:0; height:100%; overflow:hidden; }
                #map { height:100%; width:100%; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', {
                    center: [57.7679, 40.9269],
                    zoom: 13,
                    zoomControl: true,
                    attributionControl: false
                });
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                map.on('moveend', function() {
                    var center = map.getCenter();
                    Android.onCenterChanged(center.lat, center.lng);
                });

                function setCenter(lat, lng, zoom) {
                    map.setView([lat, lng], zoom);
                }

                function getCenter() {
                    var center = map.getCenter();
                    Android.onCenterRequested(center.lat, center.lng);
                }

                setTimeout(function() {
                    var center = map.getCenter();
                    Android.onCenterChanged(center.lat, center.lng);
                }, 500);
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    // ---------- Вспомогательные методы ----------
    private fun formatCoordinates(lat: Double, lon: Double): String {
        return String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon)
    }

    private fun roundedBackground(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}