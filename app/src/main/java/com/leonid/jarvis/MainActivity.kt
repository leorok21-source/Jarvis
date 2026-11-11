package com.leonid.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.speech.SpeechRecognizer

class MainActivity : Activity() {
    private lateinit var info: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 100, 36, 30)
            setBackgroundColor(Color.rgb(5, 18, 30))
        }
        val title = TextView(this).apply {
            text = "J . A . R . V . I . S ."
            textSize = 30f
            setTextColor(Color.CYAN)
        }
        panel.addView(title)
        info = TextView(this).apply {
            text = "\nГолосовой помощник — тестовая версия.\n\nПосле нажатия «Активировать» приложение использует микрофон для распознавания команд, в том числе при свёрнутом окне. На экране появляется постоянное уведомление Android.\n\nНажмите «Остановить», чтобы выключить прослушивание.\n\nКоманды: фонарик, камера, заряд, время, громкость, открытие приложений."
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        panel.addView(info)
        fun button(label: String, click: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { click() } })
        }
        button("ВКЛЮЧИТЬ МИКРОФОН И ДЖАРВИСА") { startJarvis() }
        button("ОСТАНОВИТЬ") { startService(Intent(this, JarvisService::class.java).setAction("STOP")) }
        setContentView(panel)
    }
    private fun startJarvis() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            info.text = "На устройстве нет службы распознавания речи."
            return
        }
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 99) else launchService()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchService()
    }
    private fun launchService() {
        try {
            startForegroundService(Intent(this, JarvisService::class.java).setAction("START"))
            info.text = "Джарвис активирован. Скажите «Джарвис»."
        } catch (e: Exception) { info.text = "Ошибка запуска: ${e.message}" }
    }
}
