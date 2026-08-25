package com.example.gibddochevidets

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LocationPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_location_picker
        )

        findViewById<TextView>(
            R.id.mapBack
        ).setOnClickListener {
            finish()
        }
    }
}