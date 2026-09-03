package com.example.autopaster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lines: List<String> = emptyList()
    private var currentIndex = 0
    private var delayMillis = 1000L

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        Toast.makeText(this, "دکمه شناور حذف شد", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        try {
            startForegroundWithNotification()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            setupFloatingButton()
        } catch (e: Exception) {
            // Permission revoked or window manager unavailable — stop cleanly instead of crashing.
            Toast.makeText(this, "خطا در نمایش دکمه شناور، دسترسی‌ها رو دوباره چک کن", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        delayMillis = (prefs.getFloat(Prefs.KEY_DELAY_SECONDS, 1f) * 1000L).toLong().coerceAtLeast(200L)
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "autopaster_channel"
        // minSdk is 26 (Oreo), so notification channels are always required/available.
        val channel = NotificationChannel(
            channelId, "AutoPaster", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoPaster فعال است")
            .setContentText("برای حذف دکمه شناور، آن را لمس طولانی کن")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupFloatingButton() {
        floatingButton = Button(this).apply {
            text = "▶"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
        }

        // minSdk is 26 (Oreo), so TYPE_APPLICATION_OVERLAY is always available.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        floatingButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    longPressTriggered = false
                    longPressHandler.postDelayed(longPressRunnable, 600L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        if (!moved) longPressHandler.removeCallbacks(longPressRunnable)
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    safeUpdateViewLayout(params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered) toggleRun()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingButton, params)
    }

    /** Safely attempts to update the floating button's position; never crashes the service. */
    private fun safeUpdateViewLayout(params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(floatingButton, params)
        } catch (e: Exception) {
            // View or window may have gone away (e.g. permission revoked mid-session).
        }
    }

    private fun toggleRun() {
        if (isRunning) {
            stopRun("متوقف شد")
            return
        }
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val text = prefs.getString(Prefs.KEY_TEXT, "") ?: ""
        lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "متنی برای پیست وجود نداره", Toast.LENGTH_SHORT).show()
            return
        }
        if (PasteAccessibilityService.instance == null) {
            Toast.makeText(this, "اول سرویس Accessibility رو فعال کن", Toast.LENGTH_LONG).show()
            return
        }
        currentIndex = 0
        isRunning = true
        floatingButton.text = "0/${lines.size}"
        runNextLine()
    }

    private fun stopRun(reason: String) {
        isRunning = false
        floatingButton.text = "▶"
        handler.removeCallbacksAndMessages(null)
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
    }

    private fun runNextLine() {
        if (!isRunning) return
        if (currentIndex >= lines.size) {
            stopRun("تمام شد ✅ (${lines.size} خط)")
            return
        }

        val service = PasteAccessibilityService.instance
        if (service == null) {
            stopRun("دسترسی Accessibility غیرفعال شد، متوقف کردم")
            return
        }

        val line = lines[currentIndex]
        val result = service.pasteText(line)

        when (result) {
            PasteResult.NO_FOCUSED_FIELD -> {
                stopRun("هیچ فیلد فعالی پیدا نشد. روی فیلد مقصد بزن و دوباره امتحان کن")
                return
            }
            PasteResult.FAILED -> {
                Toast.makeText(this, "پیست خط ${currentIndex + 1} ناموفق بود، ادامه میدم", Toast.LENGTH_SHORT).show()
            }
            PasteResult.OK -> { /* fine */ }
        }

        currentIndex++
        floatingButton.text = "$currentIndex/${lines.size}"
        handler.postDelayed({ runNextLine() }, delayMillis)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        if (::floatingButton.isInitialized && ::windowManager.isInitialized) {
            try {
                windowManager.removeView(floatingButton)
            } catch (e: Exception) {
                // View may already be detached (e.g. system removed it) — safe to ignore.
            }
        }
    }

    companion object {
        var isServiceRunning = false
    }
}
