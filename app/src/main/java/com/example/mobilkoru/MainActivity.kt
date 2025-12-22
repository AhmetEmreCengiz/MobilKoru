package com.example.mobilkoru

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvDesc: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: ImageButton

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private lateinit var audioManager: AudioManager

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    private var korumaAktifMi = false
    private var alarmCaliyorMu = false
    private var DOGRU_SIFRE = "1234"
    private var HASSASIYET_ESIGI = 10.2f
    private var SES_SEVIYESI_ORANI = 1.0f
    private var cekilenFotoSayisi = 0

    private val handler = Handler(Looper.getMainLooper())
    private var sesKilidiRunnable: Runnable? = null

    private var RENK_GUVENLI: Int = 0
    private var RENK_TEHLIKE: Int = 0
    private var RENK_MAVI: Int = 0

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startCamera()
            else Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        RENK_GUVENLI = ContextCompat.getColor(this, R.color.status_safe)
        RENK_TEHLIKE = ContextCompat.getColor(this, R.color.status_danger)
        RENK_MAVI = ContextCompat.getColor(this, R.color.primary_blue)

        tvStatus = findViewById(R.id.tvStatus)
        tvDesc = findViewById(R.id.tvDesc)
        btnToggle = findViewById(R.id.btnToggle)
        btnSettings = findViewById(R.id.btnSettings)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        btnSettings.setOnClickListener {
            if (korumaAktifMi) {
                Toast.makeText(this, "Korumayı durdurmadan ayarlara giremezsiniz.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        ayarlariGuncelle()
        checkOverlayPermission()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobilKoru::SensorLock")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        mediaPlayer = MediaPlayer.create(this, R.raw.siren)
        mediaPlayer?.setVolume(1.0f, 1.0f)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnToggle.setOnClickListener {
            if (korumaAktifMi) sifreSorVeDurdur()
            else korumayiBaslat()
        }
    }

    override fun onResume() {
        super.onResume()
        ayarlariGuncelle()
    }

    private fun ayarlariGuncelle() {
        val sharedPref = getSharedPreferences("MobilKoruAyarlar", Context.MODE_PRIVATE)
        DOGRU_SIFRE = sharedPref.getString("PIN", "1234") ?: "1234"
        HASSASIYET_ESIGI = sharedPref.getFloat("HASSASIYET", 10.2f)
        SES_SEVIYESI_ORANI = sharedPref.getFloat("SES_SEVIYESI", 1.0f)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (alarmCaliyorMu) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                sesiZorlaAyarla()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sesiZorlaAyarla() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * SES_SEVIYESI_ORANI).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            Log.e("MobilKoru", "Ses hatası", e)
        }
    }

    private fun alarmVer() {
        alarmCaliyorMu = true
        ekraniUyandir()

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)

        tvStatus.text = "ALARM !!!"
        tvStatus.setTextColor(RENK_TEHLIKE)
        tvDesc.text = "İZİNSİZ GİRİŞ TESPİT EDİLDİ!"

        sesiZorlaAyarla()
        sesKilidiRunnable = object : Runnable {
            override fun run() {
                if (alarmCaliyorMu) {
                    sesiZorlaAyarla()
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(sesKilidiRunnable!!)

        mediaPlayer?.start()
        mediaPlayer?.isLooping = true

        val pattern = longArrayOf(0, 100, 3000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }

        cekilenFotoSayisi = 0
        Handler(Looper.getMainLooper()).postDelayed({ seriFotografCek() }, 1500)
    }

    private fun sistemiKapat() {
        korumaAktifMi = false
        alarmCaliyorMu = false
        if (wakeLock?.isHeld == true) wakeLock?.release()

        sesKilidiRunnable?.let { handler.removeCallbacks(it) }

        tvStatus.text = "GÜVENLİ"
        tvStatus.setTextColor(RENK_GUVENLI)
        tvDesc.text = "Sistem şu an beklemede"
        btnToggle.text = "KORUMAYI BAŞLAT"
        btnToggle.setBackgroundColor(RENK_MAVI)

        sensorManager.unregisterListener(this)
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
        vibrator?.cancel()
        Toast.makeText(this, "Alarm durduruldu.", Toast.LENGTH_SHORT).show()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }

    private fun ekraniUyandir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
            } catch (exc: Exception) {
                Log.e("MobilKoru", "Kamera hatası", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun korumayiBaslat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            checkOverlayPermission()
            return
        }
        ayarlariGuncelle()
        korumaAktifMi = true
        alarmCaliyorMu = false
        wakeLock?.acquire(10 * 60 * 1000L)

        tvStatus.text = "SİSTEM AKTİF"
        tvStatus.setTextColor(RENK_TEHLIKE)
        tvDesc.text = "Hassasiyet: ${String.format("%.1f", HASSASIYET_ESIGI)}\nSes: %${(SES_SEVIYESI_ORANI * 100).toInt()}"
        btnToggle.text = "KİLİTLİ"
        btnToggle.setBackgroundColor(Color.DKGRAY)

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        Toast.makeText(this, "Sistem 3 saniye içinde aktifleşecek...", Toast.LENGTH_SHORT).show()
    }

    private fun seriFotografCek() {
        if (cekilenFotoSayisi < 3 && korumaAktifMi) {
            takePhoto()
            cekilenFotoSayisi++
            Handler(Looper.getMainLooper()).postDelayed({ seriFotografCek() }, 3000)
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MobilKoru_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MobilKoru-Hirsizlar")
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("MobilKoru", "Fotoğraf Hatası", exc)
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {}
        })
    }

    private fun sifreSorVeDurdur() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Şifre"
        val dialog = AlertDialog.Builder(this)
            .setTitle("GÜVENLİK KİLİDİ")
            .setMessage("Alarmı durdurmak için şifreyi girin:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("ONAYLA") { _, _ ->
                if (input.text.toString() == DOGRU_SIFRE) sistemiKapat()
                else Toast.makeText(this, "YANLIŞ ŞİFRE!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("İPTAL") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && korumaAktifMi) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = sqrt((x * x + y * y + z * z).toDouble())

            if (gForce > HASSASIYET_ESIGI && !alarmCaliyorMu) {
                alarmVer()
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaPlayer?.release()
        cameraExecutor.shutdown()
        vibrator?.cancel()
        sesKilidiRunnable?.let { handler.removeCallbacks(it) }
    }
}