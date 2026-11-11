package com.leonid.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle\nimport android.os.Build
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Privacy-first foreground push-to-talk release. No background mic service or autostart. */
class MainActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var listenButton: Button
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var microphoneReady = false
    private var flashlightOn = false
    private val russian = Locale("ru", "RU")
    private val permissionsCode = 45\n    private val servicePermissionsCode = 46
    private var pendingFlash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 84, 40, 32)
            setBackgroundColor(Color.rgb(8, 22, 35))
        }
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S.   1.0"
            textSize = 28f
            setTextColor(Color.CYAN)
        })
        status = TextView(this).apply {
            text = "\nК вашим услугам, сэр.\n\nПосле выдачи разрешений JARVIS работает в фоне и ждёт обращение по имени.\n\nСкажите: «Джарвис» и команду.\n\nКоманды: фонарик, время, заряд, камера, громче, тише, открой [приложение]."
            textSize = 17f
            setTextColor(Color.WHITE)
        }
        root.addView(status)
        listenButton = Button(this).apply {
            text = "🎙 НАЖАТЬ И ГОВОРИТЬ"
            setOnClickListener { requestVoice() }
        }
        root.addView(listenButton)
        root.addView(Button(this).apply {
            text = "🔦 ФОНАРИК"
            setOnClickListener { changeFlash(!flashlightOn) }
        })
        root.addView(Button(this).apply {
            text = "📷 КАМЕРА"
            setOnClickListener { openCamera() }
        })
        root.addView(Button(this).apply {
            text = "🔋 ЗАРЯД"
            setOnClickListener { battery() }
        })
        root.addView(Button(this).apply {
            text = "▶ АКТИВИРОВАТЬ ДЖАРВИС В ФОНЕ"
            setOnClickListener { ensureBackgroundService() }
        })
        root.addView(Button(this).apply {
            text = "■ ОТКЛЮЧИТЬ ФОНОВЫЙ РЕЖИМ"
            setOnClickListener {
                stopService(Intent(this@MainActivity, JarvisService::class.java))
                status.text = "Фоновый режим отключён, сэр."
            }
        })
        setContentView(root)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = russian
                tts?.setPitch(0.88f)
                tts?.setSpeechRate(0.95f)
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@MainActivity)
            }
        } else status.text = "На устройстве нет службы распознавания речи. Кнопки управления всё равно работают."
        window.decorView.post { ensureBackgroundService() }
    }

    private fun ensureBackgroundService() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), servicePermissionsCode)
            return
        }
        startJarvisService()
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "JARVIS активен в фоне. Скажите «Джарвис» и команду, сэр."
        } catch (_: Exception) {
            status.text = "Не удалось запустить фоновый режим, сэр."
        }
    }

    private fun tell(message: String) {
        status.text = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response")
    }
    private fun requestVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionsCode)
            return
        }
        startVoice()
    }
    private fun startVoice() {
        if (recognizer == null) {
            status.text = "Распознавание речи недоступно на этом телефоне."
            return
        }
        listenButton.isEnabled = false
        status.text = "Слушаю, сэр..."
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: Exception) {
            listenButton.isEnabled = true
            status.text = "Не удалось запустить распознавание речи."
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == servicePermissionsCode) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startJarvisService()
            else status.text = "Без разрешения на микрофон фоновый JARVIS не сможет слушать, сэр."
            return
        }
        if (code != permissionsCode) return
        if (permissions.contains(Manifest.permission.RECORD_AUDIO)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
            else status.text = "Без разрешения на микрофон голосовой ввод недоступен."
        }
        if (permissions.contains(Manifest.permission.CAMERA)) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && pendingFlash) {
                pendingFlash = false
                changeFlash(!flashlightOn)
            } else {
                pendingFlash = false
                status.text = "Для фонарика нужно разрешение на камеру."
            }
        }
    }
    private fun changeFlash(on: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingFlash = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), permissionsCode)
            return
        }
        try {
            val camera = getSystemService(CameraManager::class.java)
            val cameraId = camera.cameraIdList.firstOrNull { id ->
                camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) { tell("Фонарик не найден, сэр."); return }
            camera.setTorchMode(cameraId, on)
            flashlightOn = on
            tell(if (on) "Фонарик включён, сэр." else "Фонарик выключен, сэр.")
        } catch (_: Exception) { tell("Не удалось переключить фонарик, сэр.") }
    }
    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            tell("Камера открыта, сэр.")
        } catch (_: Exception) { tell("Не удалось открыть камеру, сэр.") }
    }
    private fun battery() {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        tell("Заряд аккумулятора — ${manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} процентов, сэр.")
    }
    private fun handleCommand(raw: String) {
        val cmd = raw.lowercase(russian).replace("джарвис", "").trim()
        when {
            cmd.contains("фонар") -> changeFlash(!(cmd.contains("выключ") || cmd.contains("отключ") || cmd.contains("погаси")))
            cmd.contains("время") || cmd.contains("который час") -> tell("Сейчас ${SimpleDateFormat("HH:mm", russian).format(Date())}, сэр.")
            cmd.contains("заряд") || cmd.contains("батаре") -> battery()
            cmd.contains("камер") -> openCamera()
            cmd.contains("громче") || cmd.contains("тише") -> {
                val manager = getSystemService(AUDIO_SERVICE) as AudioManager
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (cmd.contains("громче")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                tell("Громкость изменена, сэр.")
            }
            cmd.startsWith("открой ") || cmd.startsWith("запусти ") -> {
                val name = cmd.substringAfter(" ").trim()
                val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val app = list.firstOrNull { it.loadLabel(packageManager).toString().lowercase(russian).contains(name) && name.isNotBlank() }
                val launch = app?.activityInfo?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch == null) tell("Приложение не найдено, сэр.")
                else try {
                    startActivity(launch)
                    tell("Открываю ${name}, сэр.")
                } catch (_: Exception) { tell("Не удалось открыть приложение, сэр.") }
            }
            else -> tell("Пока не знаю такой команды, сэр.")
        }
    }
    override fun onResults(results: Bundle?) {
        listenButton.isEnabled = true
        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (spoken != null) handleCommand(spoken) else status.text = "Не расслышал, сэр."
    }
    override fun onError(error: Int) {
        listenButton.isEnabled = true
        status.text = "Не расслышал команду. Нажмите кнопку и повторите, сэр. (код $error)"
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onDestroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
