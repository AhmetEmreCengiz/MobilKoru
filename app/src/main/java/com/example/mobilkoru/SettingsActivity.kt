package com.example.mobilkoru

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etOldPin = findViewById<EditText>(R.id.etOldPin)
        val etNewPin = findViewById<EditText>(R.id.etNewPin)
        val btnChangePin = findViewById<Button>(R.id.btnChangePin)

        val sbSensitivity = findViewById<SeekBar>(R.id.sbSensitivity)
        val tvSensitivityValue = findViewById<TextView>(R.id.tvSensitivityValue)
        val rgVolume = findViewById<RadioGroup>(R.id.rgVolume)
        val rb25 = findViewById<RadioButton>(R.id.rb25)
        val rb50 = findViewById<RadioButton>(R.id.rb50)
        val rb75 = findViewById<RadioButton>(R.id.rb75)
        val rb100 = findViewById<RadioButton>(R.id.rb100)
        val btnSaveSettings = findViewById<Button>(R.id.btnSaveSettings)

        val sharedPref = getSharedPreferences("MobilKoruAyarlar", Context.MODE_PRIVATE)

        var kayitliPin = sharedPref.getString("PIN", "1234") ?: "1234"
        val kayitliHassasiyet = sharedPref.getFloat("HASSASIYET", 10.2f)
        val kayitliSes = sharedPref.getFloat("SES_SEVIYESI", 1.0f)

        val progressDegeri = ((20.0f - kayitliHassasiyet) * 10).toInt()
        sbSensitivity.progress = progressDegeri
        guncelleHassasiyetMetni(tvSensitivityValue, kayitliHassasiyet)

        when (kayitliSes) {
            0.25f -> rb25.isChecked = true
            0.50f -> rb50.isChecked = true
            0.75f -> rb75.isChecked = true
            else -> rb100.isChecked = true
        }

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val anlikEskik = 20.0f - (progress / 10.0f)
                guncelleHassasiyetMetni(tvSensitivityValue, anlikEskik)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnChangePin.setOnClickListener {
            val girilenEski = etOldPin.text.toString().trim()
            val girilenYeni = etNewPin.text.toString().trim()

            kayitliPin = sharedPref.getString("PIN", "1234") ?: "1234"

            if (girilenEski != kayitliPin) {
                Toast.makeText(this, "Hata: Mevcut şifre yanlış!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (girilenYeni.length != 4) {
                Toast.makeText(this, "Hata: Yeni şifre 4 haneli olmalı!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sharedPref.edit().putString("PIN", girilenYeni).apply()

            etOldPin.setText("")
            etNewPin.setText("")
            Toast.makeText(this, "Şifre başarıyla güncellendi!", Toast.LENGTH_LONG).show()

            kayitliPin = girilenYeni
        }

        btnSaveSettings.setOnClickListener {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            input.hint = "Mevcut Şifre"

            val dialog = AlertDialog.Builder(this)
                .setTitle("Ayarları Kaydet")
                .setMessage("İşlemi onaylamak için şifrenizi girin:")
                .setView(input)
                .setPositiveButton("ONAYLA") { _, _ ->
                    val girilenSifre = input.text.toString()
                    val guncelPin = sharedPref.getString("PIN", "1234")

                    if (girilenSifre == guncelPin) {
                        val editor = sharedPref.edit()

                        val sonHassasiyet = 20.0f - (sbSensitivity.progress / 10.0f)
                        editor.putFloat("HASSASIYET", sonHassasiyet)

                        val secilenSes = when (rgVolume.checkedRadioButtonId) {
                            R.id.rb25 -> 0.25f
                            R.id.rb50 -> 0.50f
                            R.id.rb75 -> 0.75f
                            else -> 1.0f
                        }
                        editor.putFloat("SES_SEVIYESI", secilenSes)

                        editor.apply()

                        Toast.makeText(this, "Ayarlar kaydedildi.", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Hata: Yanlış şifre.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("İPTAL", null)
                .create()

            dialog.show()
        }
    }

    private fun guncelleHassasiyetMetni(tv: TextView, deger: Float) {
        val durum = when {
            deger < 8.0 -> "Çok Yüksek"
            deger < 12.0 -> "Normal"
            else -> "Düşük"
        }
        tv.text = "Eşik: ${String.format("%.1f", deger)} | $durum"
    }
}