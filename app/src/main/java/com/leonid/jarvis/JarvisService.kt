package com.leonid.jarvis

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*
import android.provider.MediaStore
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

/** Experimental: Android's system recognizer is not a guaranteed always-on wake word engine. */
class JarvisService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var stopping = false
    private var activeUntil = 0L
    private var torch = false
    private var torchId: String? = null
    private val resume = Runnable { listen() }
    private val channel = "jarvis_listening"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Джарвис слушает", NotificationManager.IMPORTANCE_LOW)
        )
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.85f)
                tts?.setSpeechRate(0.94f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { speaking = false; schedule(400) } }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) { handler.post { speaking = false; schedule(500) } }
                })
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (recognizer != null) return START_NOT_STICKY
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. активен")
            .setContentText("Прослушивание команд: Джарвис")
            .setContentIntent(back).setOngoing(true).build()
        try {
            startForeground(77, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(this@JarvisService) }
            say("К вашим услугам, сэр.")
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    private fun schedule(delay: Long = 600) {
        handler.removeCallbacks(resume)
        if (!stopping && !speaking) handler.postDelayed(resume, delay)
    }
    private fun listen() {
        if (stopping || speaking) return
        try {
            recognizer?.cancel()
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: Exception) { schedule(1500) }
    }
    private fun say(message: String) {
        speaking = true
        handler.removeCallbacks(resume)
        recognizer?.cancel()
        if (tts?.isSpeaking == true) tts?.stop()
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
        if (result != TextToSpeech.SUCCESS) { speaking = false; schedule(700) }
    }
    override fun onResults(results: Bundle?) {
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase(Locale.ROOT)?.trim() ?: ""
        if (phrase.isNotBlank()) handle(phrase) else schedule()
    }
    private fun handle(phrase: String) {
        val addressed = phrase.contains("джарвис") || phrase.contains("jarvis")
        if (!addressed && System.currentTimeMillis() > activeUntil) { schedule(); return }
        val cmd = phrase.replace("джарвис", "").replace("jarvis", "").trim(' ', ',', '.', '!')
        activeUntil = System.currentTimeMillis() + 20000L
        if (cmd.isBlank()) { say("Слушаю вас, сэр."); return }
        when {
            cmd.contains("фонарик") || cmd.contains("фонарь") -> {
                val requested = !(cmd.contains("выключ") || cmd.contains("отключ") || cmd.contains("погаси"))
                try {
                    val cm = getSystemService(CameraManager::class.java)
                    if (torchId == null) torchId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    val id = torchId
                    if (id != null) { cm.setTorchMode(id, requested); torch = requested; say(if (requested) "Фонарик включён, сэр." else "Фонарик выключен, сэр.") }
                    else say("На устройстве нет фонарика, сэр.")
                } catch (_: Exception) { say("Не удалось управлять фонариком, сэр.") }
            }
            cmd.contains("заряд") || cmd.contains("батаре") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                say("Заряд аккумулятора ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} процентов, сэр.")
            }
            cmd.contains("время") || cmd.contains("который час") -> say("Сейчас ${SimpleDateFormat("HH:mm", Locale("ru")).format(Date())}, сэр.")
            cmd.contains("громче") || cmd.contains("тише") -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (cmd.contains("громче")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                say("Громкость изменена, сэр.")
            }
            cmd.contains("камер") -> {
                // Android may prohibit launching a new activity while locked/in background.
                val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(i); say("Открываю камеру, сэр.") }
                catch (_: Exception) { say("Разблокируйте телефон, чтобы открыть камеру, сэр.") }
            }
            cmd.contains("открой") || cmd.contains("запусти") -> {
                val name = cmd.replace(Regex("^(открой|запусти)\\s*"), "").trim()
                val matches = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val item = matches.firstOrNull { it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(name) && name.isNotBlank() }
                val launch = item?.activityInfo?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    try { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); say("Открываю ${name}, сэр.") }
                    catch (_: Exception) { say("Android не разрешил открыть приложение из фона, сэр.") }
                } else say("Не удалось найти приложение ${name}, сэр.")
            }
            else -> say("Пока не умею выполнять эту команду, сэр. Попробуйте фонарик, батарею, время или открытие приложения.")
        }
    }
    override fun onError(error: Int) { schedule(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1300 else 800) }
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
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
