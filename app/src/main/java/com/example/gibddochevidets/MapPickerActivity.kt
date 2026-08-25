package com.example.gibddochevidets

import java.time.ZoneId
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style

class MapPickerActivity : Activity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        private const val DEFAULT_LATITUDE = 57.7679
        private const val DEFAULT_LONGITUDE = 40.9269
        private const val DEFAULT_ZOOM = 13.5
        private val MOSCOW_ZONE = ZoneId.of("Europe/Moscow")

        // СНАЧАЛА ДЕМО-СТИЛЬ (точно работает)
        private const val MAP_STYLE = "https://demotiles.maplibre.org/style.json"
        // ПОТОМ ЗАМЕНИТЕ НА OpenFreeMap:
        // private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    }

    private lateinit var mapView: MapView
    private lateinit var root: FrameLayout
    private lateinit var coordinatesText: TextView
    private lateinit var selectButton: TextView

    private var selectedLatitude = DEFAULT_LATITUDE
    private var selectedLongitude = DEFAULT_LONGITUDE
    private var mapLibreMap: MapLibreMap? = null
    private var isMapReady = false
    private var isFinishingBySelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверка интернета
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет интернет-соединения", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Инициализация MapLibre
        try {
            MapLibre.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.e("MAP_PICKER", "MapLibre init error", e)
            Toast.makeText(this, "Ошибка инициализации карты", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        createScreen(savedInstanceState)
    }

    private fun createScreen(savedInstanceState: Bundle?) {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        // ---------- MapView ----------
        mapView = MapView(this)
        val mapParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(mapView, mapParams)
        mapView.onCreate(savedInstanceState)

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

        // Заголовок с кнопкой назад
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
            text = formatCoordinates()
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

        // ---------- Красная точка в центре ----------
        val centerPoint = TextView(this).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.rgb(220, 40, 40))
            gravity = Gravity.CENTER
            setShadowLayer(5f, 0f, 2f, Color.WHITE)
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, _ -> true }
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
            isEnabled = false
            alpha = 0.6f
            setOnClickListener {
                if (isFinishingBySelection) return@setOnClickListener
                if (!isMapReady) {
                    Toast.makeText(this@MapPickerActivity, "Карта ещё загружается", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val currentMap = mapLibreMap ?: return@setOnClickListener
                val target = currentMap.cameraPosition.target ?: return@setOnClickListener
                selectedLatitude = target.latitude
                selectedLongitude = target.longitude

                val result = Intent().apply {
                    putExtra(EXTRA_LATITUDE, selectedLatitude)
                    putExtra(EXTRA_LONGITUDE, selectedLongitude)
                }
                setResult(Activity.RESULT_OK, result)
                isFinishingBySelection = true
                finish()
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

        // Загружаем карту
        mapView.getMapAsync(this)
    }

    // ---------- OnMapReady ----------
    override fun onMapReady(map: MapLibreMap) {
        Log.d("MAP_PICKER", "Map ready")
        mapLibreMap = map

        // Проверяем размеры MapView
        mapView.post {
            Log.d("MAP_PICKER", "MapView size: ${mapView.width} x ${mapView.height}")
            if (mapView.width == 0 || mapView.height == 0) {
                Toast.makeText(this, "MapView имеет нулевой размер!", Toast.LENGTH_LONG).show()
            }
        }

        // Загружаем стиль через Style.Builder
        map.setStyle(
            Style.Builder().fromUri(MAP_STYLE)
        ) { style ->
            Log.d("MAP_PICKER", "✅ Стиль успешно загружен")
            // Устанавливаем камеру на Кострому
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                .zoom(DEFAULT_ZOOM)
                .build()

            selectedLatitude = DEFAULT_LATITUDE
            selectedLongitude = DEFAULT_LONGITUDE
            coordinatesText.text = formatCoordinates()

            // Слушатель изменения центра
            map.addOnCameraIdleListener {
                if (isFinishing || isDestroyed) return@addOnCameraIdleListener
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                selectedLatitude = target.latitude
                selectedLongitude = target.longitude
                coordinatesText.text = formatCoordinates()
            }

            isMapReady = true
            selectButton.isEnabled = true
            selectButton.alpha = 1f
        }

        // Ошибки будут видны в логах, если что-то пойдёт не так.
        // Для отладки можно перехватывать исключения через try-catch вокруг setStyle,
        // но в данном API ошибки при загрузке стиля обычно логируются внутри библиотеки.
    }

    // ---------- Жизненный цикл MapView ----------
    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.onDestroy()
        mapLibreMap = null
        super.onDestroy()
    }

    // ---------- Вспомогательные методы ----------
    private fun formatCoordinates(): String {
        return String.format(
            java.util.Locale.US,
            "%.6f, %.6f",
            selectedLatitude,
            selectedLongitude
        )
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
}