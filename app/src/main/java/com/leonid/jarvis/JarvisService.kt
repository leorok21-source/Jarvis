package com.leonid.jarvis

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.*
import android.provider.MediaStore
import android.provider.AlarmClock
import android.app.SearchManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class JarvisService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private lateinit var ai: InferenceEngine
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var aiReady = false
    private var ttsReady = false
    private var modelReady = false
    private var stopping = false
    private var activeUntil = 0L
    private var torchId: String? = null
    private val channelId = "jarvis_active"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> speech?.setPause(true)
                Intent.ACTION_USER_PRESENT -> if (!stopping) speech?.setPause(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        initVoice()
        initOfflineModel()
        initLocalAi()
    }

    private fun initLocalAi() {
        val file = File(getExternalFilesDir(null), "jarvis-qwen3-0.6b-q4.gguf")
        if (!file.exists()) return
        aiScope.launch {
            try {
                ai = AiChat.getInferenceEngine(this@JarvisService)
                ai.loadModel(file.absolutePath)
                ai.setSystemPrompt(
                    """Ты JARVIS, мужской помощник Леонида. Отвечай по-русски, кратко и уважительно, обращаясь «сэр».
Для действий телефона верни только JSON без пояснений:
{"action":"flash_on|flash_off|camera|volume_up|volume_down|open_app|timer|alarm|map|web|answer","value":"параметр"}.
timer value — минуты числом; alarm value — время HH:mm; open_app — название; map — место; web — запрос.
Если это вопрос или разговор, action=answer и value=твой ответ."""
                )
                aiReady = true
                updateNotification("Офлайн-ИИ готов • скажите «Джарвис»")
            } catch (_: Exception) {
                updateNotification("Голосовые команды готовы • ИИ не загрузился")
            }
        }
    }

    private fun initVoice() {
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.82f)
                tts?.setSpeechRate(0.93f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { resumeListening() } }
                    @Deprecated("Deprecated")
                    override fun onError(id: String?) { handler.post { resumeListening() } }
                })
                ttsReady = true
                maybeActivate()
            }
        }
    }

    private fun initOfflineModel() {
        Thread {
            try {
                val directory = File(filesDir, "vosk-model-small-ru-0.22")
                val marker = File(directory, "conf/model.conf")
                if (!marker.exists()) unpackModel(directory)
                model = Model(directory.absolutePath)
                modelReady = true
                handler.post { maybeActivate() }
            } catch (_: Exception) {
                updateNotification("Ошибка загрузки офлайн-модели")
            }
        }.start()
    }

    private fun unpackModel(destination: File) {
        val root = filesDir.canonicalFile
        assets.open("vosk-model-small-ru-0.22.zip").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val output = File(filesDir, entry.name).canonicalFile
                    if (!output.path.startsWith(root.path + File.separator)) throw SecurityException("Invalid model path")
                    if (entry.isDirectory) output.mkdirs()
                    else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (!File(destination, "conf/model.conf").exists()) throw IllegalStateException("Model unpack failed")
    }

    private fun maybeActivate() {
        if (!modelReady || !ttsReady || stopping) return
        updateNotification("Офлайн-ядро готово • скажите «Джарвис»")
        say("Джарвис активирован. К вашим услугам, сэр.")
    }

    private fun startListening() {
        if (speech != null || stopping) return
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speech = SpeechService(recognizer, 16000.0f).also { it.startListening(this) }
        } catch (_: Exception) {
            updateNotification("Не удалось запустить микрофон")
        }
    }

    private fun resumeListening() {
        if (speech == null) startListening() else speech?.setPause(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_JARVIS") {
            say("Отключаюсь, сэр.")
            handler.postDelayed({ stopSelf() }, 900)
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, JarvisService::class.java).setAction("STOP_JARVIS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. запускает ядро")
            .setContentText("Подготовка локального распознавания…")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Отключить", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(77, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(77, notification)
        if (modelReady && ttsReady) maybeActivate()
        return START_STICKY
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "JARVIS работает", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
    }

    private fun updateNotification(message: String) {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. активен")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(77, notification)
    }

    private fun textFrom(json: String, key: String): String =
        try { JSONObject(json).optString(key).lowercase(Locale.ROOT).trim() } catch (_: Exception) { "" }

    override fun onResult(hypothesis: String?) {
        val phrase = textFrom(hypothesis.orEmpty(), "text")
        if (phrase.isNotBlank()) handle(phrase)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {
        val phrase = textFrom(hypothesis.orEmpty(), "text")
        if (phrase.isNotBlank()) handle(phrase)
    }
    override fun onError(exception: Exception?) {
        updateNotification("Ошибка микрофона • откройте JARVIS")
    }
    override fun onTimeout() {}

    private fun handle(phrase: String) {
        val addressed = phrase.contains("джарвис") || phrase.contains("jarvis")
        if (!addressed && System.currentTimeMillis() > activeUntil) return
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
            command.contains("время") || command.contains("который час") ->
                say("Сейчас " + SimpleDateFormat("HH:mm", Locale("ru")).format(Date()) + ", сэр.")
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
            command.startsWith("открой ") || command.startsWith("запусти ") ->
                openApplication(command.substringAfter(" ").trim())
            command.contains("отключись") || command.contains("перестань слушать") -> {
                say("Отключаюсь, сэр.")
                handler.postDelayed({ stopSelf() }, 900)
            }
            else -> if (aiReady) askLocalAi(command) else say("Локальный интеллект ещё загружается, сэр.")
        }
    }

    private fun askLocalAi(command: String) {
        speech?.setPause(true)
        updateNotification("Обдумываю запрос локально…")
        aiScope.launch {
            try {
                val response = StringBuilder()
                ai.sendUserPrompt(command, 180).collect { response.append(it) }
                handler.post { executeAiResponse(response.toString()) }
            } catch (_: Exception) {
                handler.post { say("Не удалось обработать запрос локально, сэр.") }
            }
        }
    }

    private fun executeAiResponse(raw: String) {
        try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            val data = JSONObject(raw.substring(start, end + 1))
            val action = data.optString("action")
            val value = data.optString("value")
            when (action) {
                "flash_on" -> changeFlash(true)
                "flash_off" -> changeFlash(false)
                "camera" -> openCamera()
                "volume_up" -> adjustVolume(true)
                "volume_down" -> adjustVolume(false)
                "open_app" -> openApplication(value)
                "timer" -> openTimer(value.toIntOrNull() ?: 1)
                "alarm" -> openAlarm(value)
                "map" -> openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(value))), "Открываю маршрут, сэр.")
                "web" -> openExternal(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, value), "Ищу информацию, сэр.")
                else -> say(if (value.isBlank()) raw.take(500) else value.take(500))
            }
        } catch (_: Exception) {
            say(raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim().take(500))
        }
    }

    private fun adjustVolume(up: Boolean) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        say("Громкость изменена, сэр.")
    }

    private fun openTimer(minutes: Int) {
        openExternal(
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, minutes.coerceAtLeast(1) * 60).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Таймер установлен, сэр."
        )
    }

    private fun openAlarm(value: String) {
        val parts = value.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        openExternal(
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Будильник установлен, сэр."
        )
    }

    private fun openExternal(intent: Intent, confirmation: String) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            say(confirmation)
        } catch (_: Exception) { say("Android не разрешил выполнить это действие, сэр.") }
    }

    private fun say(message: String) {
        speech?.setPause(true)
        val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.currentTimeMillis())
        if (result != TextToSpeech.SUCCESS) resumeListening()
    }

    private fun changeFlash(on: Boolean) {
        try {
            val manager = getSystemService(CameraManager::class.java)
            if (torchId == null) torchId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            val id = torchId
            if (id == null) say("Фонарик не найден, сэр.")
            else {
                manager.setTorchMode(id, on)
                say(if (on) "Фонарик включён, сэр." else "Фонарик выключен, сэр.")
            }
        } catch (_: Exception) { say("Не удалось переключить фонарик, сэр.") }
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            say("Открываю камеру, сэр.")
        } catch (_: Exception) { say("Android не разрешил открыть камеру из фона, сэр.") }
    }

    private fun openApplication(name: String) {
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val item = apps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(name) && name.isNotBlank()
        }
        val launch = item?.activityInfo?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) say("Приложение не найдено, сэр.")
        else try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            say("Открываю " + name + ", сэр.")
        } catch (_: Exception) { say("Android не разрешил открыть приложение из фона, сэр.") }
    }

    override fun onDestroy() {
        stopping = true
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        speech?.stop()
        speech?.shutdown()
        model?.close()
        aiScope.cancel()
        if (::ai.isInitialized) ai.destroy()
        tts?.stop()
        tts?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
