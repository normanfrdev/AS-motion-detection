
package com.normanfr.motiondetection

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("MotionDetectionPrefs", MODE_PRIVATE)

        val ipEditText: EditText = findViewById(R.id.ip_address)
        val sensitivityEditText: EditText = findViewById(R.id.sensitivity)
        val saveButton: Button = findViewById(R.id.save_button)

        //load preferences
        ipEditText.setText(sharedPreferences.getString("server_ip", ""))
        sensitivityEditText.setText(sharedPreferences.getInt("sensitivity", 10000).toString())

        saveButton.setOnClickListener {
            val editor = sharedPreferences.edit()
            editor.putString("server_ip", ipEditText.text.toString())
            editor.putInt("sensitivity", sensitivityEditText.text.toString().toIntOrNull() ?: 10000)
            editor.apply()
            finish()
        }
    }
}
