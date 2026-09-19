package com.example.pokercapture

import android.app.*
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "capture.start"
        const val ACTION_STOP = "capture.stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "capture"
    }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var lastSignature: Long? = null
    private var lastSavedAt = 0L
    private var tableWasPresent = false
    private var heroActionWasVisible = false
    private var screenshotObserver: ContentObserver? = null
    private var lastScreenshotId = -1L
    private var projectionCallback: MediaProjection.Callback? = null
    private var overlayButton: Button? = null
    private var windowManager: WindowManager? = null
    private val latestFrameLock = Any()
    private var latestFrame: Bitmap? = null

    private fun selectedAiPackage(): String {
        return when (getSharedPreferences("capture", MODE_PRIVATE).getString("ai_model", "CHATGPT")) {
            "GEMINI" -> "com.google.android.apps.bard"
            "CLAUDE" -> "com.anthropic.claude"
            else -> "com.openai.chatgpt"
        }
    }

    private fun selectedAiLabel(): String {
        return when (getSharedPreferences("capture", MODE_PRIVATE).getString("ai_model", "CHATGPT")) {
            "GEMINI" -> "Gemini"
            "CLAUDE" -> "Claude"
            else -> "ChatGPT"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        startScreenshotWatcher()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_START) {
            startForeground(1, NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Poker Capture").setContentText("Screen capture active")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build())
            startCapture(intent)
            showOverlayButton()
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)!! else intent.getParcelableExtra(EXTRA_DATA)!!
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                reader?.close()
                reader = null
                projection = null
                stopSelf()
            }
        }
        projection!!.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay("PokerCapture", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        reader!!.setOnImageAvailableListener({ r -> processFrame(r) }, null)
    }
    private fun processFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            val clean = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            // Keep one completed frame ready for the CAP button. The ImageReader listener is
            // the only consumer; CAP must not race it by acquiring images independently.
            synchronized(latestFrameLock) {
                latestFrame?.recycle()
                latestFrame = clean.copy(Bitmap.Config.ARGB_8888, false)
            }
            val x0 = (clean.width * 0.08).toInt(); val x1 = (clean.width * 0.92).toInt()
            val y0 = (clean.height * 0.18).toInt(); val y1 = (clean.height * 0.72).toInt()
            var sig = 0L; var samples = 0; var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { sig += clean.getPixel(x, y).toLong() and 0x00FFFFFF; samples++; x += 48 }
                y += 48
            }
            sig /= samples.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            // Guard: only save when the PokerStars table is likely visible.
            // Detect the stable green felt in the central table area; navigating to other apps should fail this test.
            var green = 0
            var total = 0
            var gy = (clean.height * 0.28).toInt()
            val gy1 = (clean.height * 0.68).toInt()
            while (gy < gy1) {
                var gx = (clean.width * 0.12).toInt()
                val gx1 = (clean.width * 0.88).toInt()
                while (gx < gx1) {
                    val p = clean.getPixel(gx, gy)
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    if (gg > rr * 1.18 && gg > bb * 1.12 && gg > 45) green++
                    total++
                    gx += 32
                }
                gy += 32
            }
            val tablePresent = total > 0 && green.toDouble() / total > 0.18

            // Hero-turn trigger calibrated from the supplied 832x1852 screenshots.
            // Only inspect the large bottom decision buttons (Fold / Check / Call / Bet / Raise).
            var buttonPixels = 0
            var buttonTotal = 0
            var ay = (clean.height * 0.875).toInt()
            val ay1 = (clean.height * 0.935).toInt()
            while (ay < ay1) {
                var ax = (clean.width * 0.03).toInt()
                val ax1 = (clean.width * 0.97).toInt()
                while (ax < ax1) {
                    val p = clean.getPixel(ax, ay)
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    // Poker action buttons are strongly saturated blue/green/orange/red or dark-gray Fold.
                    val saturated = maxOf(rr, gg, bb) - minOf(rr, gg, bb) > 55 && maxOf(rr, gg, bb) > 105
                    val foldGray = rr in 45..115 && gg in 45..115 && bb in 45..125 && kotlin.math.abs(rr - gg) < 25
                    if (saturated || foldGray) buttonPixels++
                    buttonTotal++
                    ax += 12
                }
                ay += 12
            }
            val heroActionVisible = tablePresent && buttonTotal > 0 &&
                buttonPixels.toDouble() / buttonTotal > 0.20

            if (heroActionVisible && !heroActionWasVisible && now - lastSavedAt > 900) {
                saveFrame(clean, now)
                lastSavedAt = now
                sendBroadcast(Intent("com.example.pokercapture.FRAME_SAVED").setPackage(packageName))
            }
            heroActionWasVisible = heroActionVisible
            tableWasPresent = tablePresent
            if (tablePresent) lastSignature = sig
            clean.recycle()
        } finally { image.close() }
    }
    private fun saveFrame(bitmap: Bitmap, ts: Long) {
        // Crop according to the user's tablet/split-screen orientation preference.
        val cropMode = getSharedPreferences("capture", MODE_PRIVATE).getString("crop_mode", "RIGHT") ?: "RIGHT"
        val half = when (cropMode) {
            "LEFT" -> Bitmap.createBitmap(bitmap, 0, 0, (bitmap.width / 2).coerceAtLeast(1), bitmap.height)
            "TOP" -> Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height / 2).coerceAtLeast(1))
            "BOTTOM" -> Bitmap.createBitmap(bitmap, 0, bitmap.height / 2, bitmap.width, (bitmap.height - bitmap.height / 2).coerceAtLeast(1))
            else -> Bitmap.createBitmap(bitmap, bitmap.width / 2, 0, (bitmap.width - bitmap.width / 2).coerceAtLeast(1), bitmap.height)
        }
        val targetWidth = (half.width * 0.70f).toInt().coerceAtLeast(1)
        val targetHeight = (half.height * 0.70f).toInt().coerceAtLeast(1)
        val output = Bitmap.createScaledBitmap(half, targetWidth, targetHeight, true)
        half.recycle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PokerCapture_$ts.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PokerCapture")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            contentResolver.openOutputStream(uri)?.use {
                output.compress(Bitmap.CompressFormat.JPEG, 60, it)
            } ?: throw IllegalStateException("Could not open MediaStore output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            val prefs = getSharedPreferences("capture", MODE_PRIVATE)
            prefs.edit().putInt("frames_saved", prefs.getInt("frames_saved", 0) + 1).apply()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
        } finally {
            if (output !== bitmap) output.recycle()
        }
    }

    private fun showOverlayButton() {
        if (overlayButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = "CAP"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(210, 30, 30, 30)) }
            setOnClickListener { captureWhenFrameReady() }
        }
        val size = (80 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * resources.displayMetrics.density).toInt()
        }
        windowManager?.addView(button, params)
        overlayButton = button
    }

    private fun captureWhenFrameReady(attempt: Int = 0) {
        val frame = synchronized(latestFrameLock) {
            latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (frame == null) {
            if (attempt < 15) Handler(Looper.getMainLooper()).postDelayed({ captureWhenFrameReady(attempt + 1) }, 50)
            return
        }
        captureAndNotify(frame)
    }

    private fun captureAndNotify(clean: Bitmap) {
        try {
            val ts = System.currentTimeMillis()
            saveFrame(clean, ts)
            val uri = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DISPLAY_NAME + "=?",
                arrayOf("PokerCapture_$ts.jpg"),
                null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cur.getLong(0)
                    )
                } else null
            } ?: return

            // The user keeps the desired ChatGPT conversation already open and active.
            // Put the freshly saved image on the Android clipboard and let Accessibility
            // paste it into that exact composer. No ACTION_SEND, no new-chat launch.
            try {
                grantUriPermission(
                    selectedAiPackage(),
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "PokerCapture", uri))
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "CAP: αποτυχία αντιγραφής εικόνας", Toast.LENGTH_SHORT).show()
                }
                return
            }

            if (!ChatGptAccessibilityService.pasteAndSend()) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "CAP: ενεργοποίησε το Poker Capture Accessibility για " + selectedAiLabel(),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } finally {
            clean.recycle()
        }
    }

    private fun removeOverlayButton() {
        overlayButton?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayButton = null
    }

    private fun stopCapture() {
        synchronized(latestFrameLock) { latestFrame?.recycle(); latestFrame = null }
        reader?.close(); reader = null
        projectionCallback?.let { callback -> projection?.unregisterCallback(callback) }
        projectionCallback = null
        projection?.stop(); projection = null
    }
    override fun onDestroy() {
        removeOverlayButton(); stopScreenshotWatcher(); stopCapture(); super.onDestroy() }
    private fun startScreenshotWatcher() {
        if (screenshotObserver != null) return
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                shareLatestScreenshotToChatGPT()
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver!!)
    }

    private fun stopScreenshotWatcher() {
        screenshotObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        screenshotObserver = null
    }

    private fun shareLatestScreenshotToChatGPT() {
        val projectionCols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH)
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projectionCols, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
            if (!cur.moveToFirst()) return
            val id = cur.getLong(0)
            val name = cur.getString(1) ?: ""
            val rel = cur.getString(2) ?: ""
            if (id == lastScreenshotId || (!name.contains("screenshot", true) && !rel.contains("screenshot", true))) return
            lastScreenshotId = id
            val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(selectedAiPackage())
            }
            val pending = PendingIntent.getActivity(
                this, id.toInt(), send,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Screenshot ready")
                .setContentText("Tap to send to " + selectedAiLabel())
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            getSystemService(NotificationManager::class.java).notify(1001, notification)
        }
    }
}
