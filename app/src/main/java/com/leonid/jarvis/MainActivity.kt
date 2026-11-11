package com.leonid.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val permissionCode = 46
    private val bg = Color.rgb(2, 10, 18)
    private val cyan = Color.rgb(0, 229, 255)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildInterface()
        window.decorView.post { ensurePermissionsAndStart() }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(36))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "SYSTEM ONLINE"
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = .28f
            setTextColor(Color.rgb(100, 185, 210))
            setPadding(0, dp(4), 0, dp(22))
        })
        root.addView(ArcReactorView(this), LinearLayout.LayoutParams(dp(300), dp(300)))
        status = TextView(this).apply {
            text = "ИНИЦИАЛИЗАЦИЯ ЯДРА…"
            textSize = 15f
            gravity = Gravity.CENTER
            letterSpacing = .12f
            setTextColor(Color.rgb(160, 230, 245))
            setPadding(0, dp(26), 0, 0)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Скажите «Джарвис» и команду"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 145, 165))
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(root)
    }

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionCode)
        else startJarvis()
    }

    private fun startJarvis() {
        try {
            val intent = Intent(this, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "ЯДРО АКТИВНО"
        } catch (_: Exception) {
            status.text = "ОШИБКА ЗАПУСКА ЯДРА"
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code != permissionCode) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startJarvis()
        } else status.text = "ТРЕБУЕТСЯ ДОСТУП К МИКРОФОНУ"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private class ArcReactorView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var rotation = 0f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * .42f
            val pulse = 1f + sin(phase.toDouble()).toFloat() * .035f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(30, 0, 229, 255)
            paint.setShadowLayer(base * .35f, 0f, 0f, Color.CYAN)
            canvas.drawCircle(cx, cy, base * pulse, paint)
            paint.clearShadowLayer()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpF(3f)
            paint.color = Color.rgb(0, 210, 255)
            paint.setShadowLayer(18f, 0f, 0f, Color.CYAN)
            canvas.drawCircle(cx, cy, base * .78f * pulse, paint)
            paint.clearShadowLayer()

            paint.strokeWidth = dpF(9f)
            paint.strokeCap = Paint.Cap.ROUND
            val rect = RectF(cx - base * .62f, cy - base * .62f, cx + base * .62f, cy + base * .62f)
            for (i in 0 until 12) {
                paint.color = if (i % 3 == 0) Color.WHITE else Color.rgb(0, 190, 240)
                canvas.drawArc(rect, rotation + i * 30f, 17f, false, paint)
            }

            paint.strokeWidth = dpF(2f)
            paint.color = Color.rgb(80, 235, 255)
            canvas.drawCircle(cx, cy, base * .40f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.setShadowLayer(22f, 0f, 0f, Color.CYAN)
            canvas.drawCircle(cx, cy, base * .27f * pulse, paint)
            paint.clearShadowLayer()

            paint.color = Color.rgb(0, 120, 170)
            canvas.drawCircle(cx, cy, base * .12f, paint)

            phase += .07f
            rotation = (rotation + .55f) % 360f
            postInvalidateOnAnimation()
        }

        private fun dpF(value: Float) = value * resources.displayMetrics.density
    }
}
