package com.leonid.jarvis

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*
import android.provider.MediaStore
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var stopping = false
    private var ready = false
    private var activeUntil = 0L
    private var torchId: String? = null
    private val channelId = "jarvis_active"
    private val listenAgain = Runnable { listen() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(this@JarvisService)
        }
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.82f)
                tts?.setSpeechRate(0.93f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) = resumeAfterSpeech()
                    @Deprecated("Deprecated")
                    override fun onError(id: String?) = resumeAfterSpeech()
                })
                ready = true
                say("Джарвис активирован. К вашим услугам, сэр.")
            } else schedule(1200)
        }
    }

    private fun resumeAfterSpeech() {
        handler.post {
            speaking = false
            schedule(450)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_JARVIS") {
            say("Отключаюсь, сэр.")
            handler.postDelayed({ stopSelf() }, 900)
            return START_NOT_STICKY
        }
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, JarvisService::class.java).setAction("STOP_JARVIS")
        val stop = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. активен")
            .setContentText("Скажите «Джарвис» и команду")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "Отключить", stop)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(77, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(77, notification)
            if (ready && !speaking) schedule(250)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId, "JARVIS работает", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновое распознавание обращения «Джарвис»"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun phoneAvailable(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun schedule(delay: Long = 650) {
        handler.removeCallbacks(listenAgain)
        if (!stopping && !speaking) handler.postDelayed(listenAgain, delay)
    }

    private fun listen() {
        if (stopping || speaking || !ready) return
        if (!phoneAvailable()) {
            schedule(2500)
            return
        }
        try {
            recognizer?.cancel()
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            })
        } catch (_: Exception) {
            schedule(1500)
        }
    }

    private fun say(message: String) {
        speaking = true
        handler.removeCallbacks(listenAgain)
        recognizer?.cancel()
        tts?.stop()
        val result = tts?.speak(
            message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.currentTimeMillis()
        )
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            schedule(800)
        }
    }

    override fun onResults(results: Bundle?) {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val phrase = candidates.firstOrNull {
            it.lowercase(Locale.ROOT).contains("джарвис") || it.lowercase(Locale.ROOT).contains("jarvis")
        } ?: candidates.firstOrNull().orEmpty()
        handle(phrase.lowercase(Locale.ROOT).trim())
    }

    private fun handle(phrase: String) {
        val addressed = phrase.contains("джарвис") || phrase.contains("jarvis")
        if (!addressed && System.currentTimeMillis() > activeUntil) {
            schedule(350)
            return
        }
        val command = phrase.replace("джарвис", "").replace("jarvis", "")
            .trim(' ', ',', '.', '!', '?')
        activeUntil = System.currentTimeMillis() + 15_000L
        when {
            command.isBlank() -> say("Слушаю вас, сэр.")
            command.contains("фонар") -> changeFlash(
                !(command.contains("выключ") || command.contains("отключ") || command.contains("погаси"))
            )
            command.contains("заряд") || command.contains("батаре") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                say("Заряд аккумулятора " + battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " процентов, сэр.")
            }
            command.contains("время") || command.contains("который час") -> {
                say("Сейчас " + SimpleDateFormat("HH:mm", Locale("ru")).format(Date()) + ", сэр.")
            }
            command.contains("громче") || command.contains("тише") -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (command.contains("громче")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                say("Громкость изменена, сэр.")
            }
            command.contains("камер") -> openCamera()
            command.startsWith("открой ") || command.startsWith("запусти ") -> {
                openApplication(command.substringAfter(" ").trim())
            }
            command.contains("отключись") || command.contains("перестань слушать") -> {
                say("Отключаюсь, сэр.")
                handler.postDelayed({ stopSelf() }, 900)
            }
            else -> say("Эту команду я пока не выполняю, сэр.")
        }
    }

    private fun changeFlash(on: Boolean) {
        try {
            val manager = getSystemService(CameraManager::class.java)
            if (torchId == null) {
                torchId = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }
            val id = torchId
            if (id == null) say("Фонарик не найден, сэр.")
            else {
                manager.setTorchMode(id, on)
                say(if (on) "Фонарик включён, сэр." else "Фонарик выключен, сэр.")
            }
        } catch (_: Exception) {
            say("Не удалось переключить фонарик, сэр.")
        }
    }

    private fun openCamera() {
        try {
            startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            say("Открываю камеру, сэр.")
        } catch (_: Exception) {
            say("Android не разрешил открыть камеру из фона, сэр.")
        }
    }

    private fun openApplication(name: String) {
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val item = apps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(name) &&
                name.isNotBlank()
        }
        val launch = item?.activityInfo?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            say("Приложение не найдено, сэр.")
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            say("Открываю " + name + ", сэр.")
        } catch (_: Exception) {
            say("Android не разрешил открыть приложение из фона, сэр.")
        }
    }

    override fun onError(error: Int) {
        schedule(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1400 else 700)
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
